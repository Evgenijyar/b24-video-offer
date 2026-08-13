package ru.abs7.videooffer.tenant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.videooffer.bitrix.BitrixRestException;

import java.util.*;

@Service
public class TenantAdminService {
    private final VideoOfferTenantRepository tenantRepository;
    private final VideoOfferTenantUserRepository userRepository;
    private final BitrixWebhookClient webhookClient;
    private final TenantAccessService accessService;

    public TenantAdminService(
            VideoOfferTenantRepository tenantRepository,
            VideoOfferTenantUserRepository userRepository,
            BitrixWebhookClient webhookClient,
            TenantAccessService accessService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.webhookClient = webhookClient;
        this.accessService = accessService;
    }

    public List<TenantSummary> list() {
        return tenantRepository.findAll().stream()
                .sorted(Comparator.comparing(VideoOfferTenant::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::summary)
                .toList();
    }

    public TenantDetails details(long tenantId) {
        VideoOfferTenant tenant = requiredTenant(tenantId);
        List<TenantUserView> users = userRepository.findAllByTenantIdOrderByDisplayNameAsc(tenantId).stream()
                .map(TenantUserView::from)
                .toList();
        long storage = accessService.currentStorageBytes(tenantId);
        return TenantDetails.from(tenant, users, storage);
    }

    @Transactional
    public TenantDetails create(CreateTenantRequest request) {
        String normalizedDomain = VideoOfferTenant.normalizeDomain(request.portalDomain());
        String webhook = request.webhookUrl() == null || request.webhookUrl().isBlank()
                ? null : webhookClient.normalizeWebhookUrl(request.webhookUrl());
        Optional<VideoOfferTenant> existing = tenantRepository.findByPortalDomainIgnoreCase(normalizedDomain);
        if (existing.isPresent()) {
            VideoOfferTenant draft = existing.get();
            if (draft.getStatus() != TenantStatus.PENDING) {
                throw new IllegalArgumentException("Клиент с порталом " + normalizedDomain + " уже зарегистрирован");
            }
            int seats = positive(request.seatLimit(), draft.getSeatLimit());
            long alreadyEnabled = userRepository.countByTenantIdAndOfferAccessTrueAndActiveTrue(draft.getId());
            seats = Math.max(seats, (int) Math.min(Integer.MAX_VALUE, alreadyEnabled));
            draft.updateMasterSettings(
                    request.name(), normalizedDomain, webhook,
                    request.localClientId() == null ? draft.getLocalClientId() : request.localClientId(),
                    request.localClientSecret() == null ? draft.getLocalClientSecret() : request.localClientSecret(),
                    TenantStatus.PENDING,
                    request.packageName(), seats, positive(request.offerLimit(), draft.getOfferLimit()),
                    quotaBytes(request.diskQuotaGb()), Boolean.TRUE.equals(request.allowAnyEntity()));
            tenantRepository.saveAndFlush(draft);
            return details(draft.getId());
        }
        VideoOfferTenant tenant = VideoOfferTenant.create(
                request.name(), normalizedDomain, webhook,
                request.localClientId(), request.localClientSecret(),
                request.packageName(), positive(request.seatLimit(), 3), positive(request.offerLimit(), 50),
                quotaBytes(request.diskQuotaGb()), Boolean.TRUE.equals(request.allowAnyEntity()));
        tenant = tenantRepository.saveAndFlush(tenant);
        return details(tenant.getId());
    }

    @Transactional
    public TenantDetails update(long tenantId, UpdateTenantRequest request) {
        VideoOfferTenant tenant = requiredTenant(tenantId);
        String normalizedDomain = VideoOfferTenant.normalizeDomain(request.portalDomain());
        tenantRepository.findByPortalDomainIgnoreCase(normalizedDomain)
                .filter(other -> !other.getId().equals(tenantId))
                .ifPresent(other -> { throw new IllegalArgumentException("Портал " + normalizedDomain + " уже привязан к другому клиенту"); });
        String webhook = request.webhookUrl() == null || request.webhookUrl().isBlank()
                ? null : webhookClient.normalizeWebhookUrl(request.webhookUrl());
        int requestedSeats = positive(request.seatLimit(), tenant.getSeatLimit());
        long currentlyEnabled = userRepository.countByTenantIdAndOfferAccessTrueAndActiveTrue(tenantId);
        if (requestedSeats < currentlyEnabled) {
            throw new IllegalArgumentException("Нельзя уменьшить лимит сотрудников ниже уже подключённых: " + currentlyEnabled);
        }
        tenant.updateMasterSettings(
                request.name(), normalizedDomain, webhook,
                request.localClientId() == null ? tenant.getLocalClientId() : request.localClientId(),
                request.localClientSecret() == null ? tenant.getLocalClientSecret() : request.localClientSecret(),
                request.status(),
                request.packageName(), requestedSeats, positive(request.offerLimit(), tenant.getOfferLimit()),
                quotaBytes(request.diskQuotaGb()), Boolean.TRUE.equals(request.allowAnyEntity()));
        tenantRepository.saveAndFlush(tenant);
        return details(tenantId);
    }

    public ConnectionTest testConnection(long tenantId) {
        VideoOfferTenant tenant = requiredTenant(tenantId);
        if (tenant.getWebhookUrl() == null || tenant.getWebhookUrl().isBlank()) {
            return new ConnectionTest(false, "Входящий вебхук не настроен", null, 0);
        }
        try {
            Map<String, Object> current = webhookClient.call(tenant.getWebhookUrl(), "user.current", Map.of());
            Map<String, Object> user = map(current.get("result"));
            webhookClient.call(tenant.getWebhookUrl(), "crm.item.fields", Map.of("entityTypeId", 1));
            Map<String, Object> usersResponse = webhookClient.call(tenant.getWebhookUrl(), "user.get", Map.of(
                    "FILTER", Map.of("USER_TYPE", "employee"),
                    "start", 0));
            int count = collectionSize(usersResponse.get("result"));
            long total = positiveLong(usersResponse.get("total"));
            if (total > count) count = (int) Math.min(Integer.MAX_VALUE, total);
            return new ConnectionTest(true, "Подключение работает", joinName(user), count);
        } catch (BitrixRestException error) {
            String message = "TRANSPORT_ERROR".equalsIgnoreCase(error.getErrorCode())
                    ? "Bitrix24 не ответил. Проверьте подключение и повторите проверку"
                    : "Bitrix24 отклонил запрос: " + safeBitrixMessage(error.getMessage());
            return new ConnectionTest(false, message, null, 0);
        }
    }

    @Transactional
    public TenantDetails syncUsers(long tenantId) {
        VideoOfferTenant tenant = requiredTenant(tenantId);
        List<BitrixUserSnapshot> snapshots = loadBitrixUsers(tenant);
        Set<Long> seen = new HashSet<>();
        for (BitrixUserSnapshot snapshot : snapshots) {
            seen.add(snapshot.id());
            VideoOfferTenantUser user = userRepository.findByTenantIdAndBitrixUserId(tenantId, snapshot.id())
                    .orElseGet(() -> VideoOfferTenantUser.fromBitrix(tenantId, snapshot.id(), snapshot.name(), snapshot.email()));
            user.synchronizeProfile(snapshot.name(), snapshot.email(), snapshot.active());
            userRepository.save(user);
        }
        for (VideoOfferTenantUser existing : userRepository.findAllByTenantIdOrderByDisplayNameAsc(tenantId)) {
            if (!seen.contains(existing.getBitrixUserId())) {
                existing.synchronizeProfile(existing.getDisplayName(), existing.getEmail(), false);
                userRepository.save(existing);
            }
        }
        userRepository.flush();
        return details(tenantId);
    }

    @Transactional
    public TenantDetails setPrimaryAdmin(long tenantId, long userId) {
        VideoOfferTenant tenant = requiredTenant(tenantId);
        VideoOfferTenantUser selected = userRepository.findByTenantIdAndBitrixUserId(tenantId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Сотрудник Bitrix24 не найден в синхронизированном списке"));
        if (!selected.isActive()) {
            throw new IllegalArgumentException("Нельзя назначить неактивного сотрудника главным администратором");
        }
        List<VideoOfferTenantUser> users = userRepository.findAllByTenantIdOrderByDisplayNameAsc(tenantId);
        long seats = userRepository.countByTenantIdAndOfferAccessTrueAndActiveTrue(tenantId);
        VideoOfferTenantUser previousPrimary = users.stream().filter(VideoOfferTenantUser::isPrimaryAdmin).findFirst().orElse(null);
        boolean transferSeat = !selected.hasOfferAccess() && seats >= tenant.getSeatLimit();
        if (transferSeat && (previousPrimary == null || previousPrimary.getBitrixUserId() == userId || !previousPrimary.hasOfferAccess())) {
            throw new IllegalArgumentException("Сначала освободите место в лимите сотрудников: " + tenant.getSeatLimit());
        }
        for (VideoOfferTenantUser user : users) {
            boolean isSelected = user.getBitrixUserId() == userId;
            user.markPrimaryAdmin(isSelected);
            // If the package has no free seat, changing the primary administrator is a real seat transfer:
            // the former primary loses ordinary access, while its personal templates remain stored.
            if (transferSeat && previousPrimary != null && user.getBitrixUserId().equals(previousPrimary.getBitrixUserId()) && !isSelected) {
                user.configure(false, false, user.getDefaultAccompanyingText(), user.getDefaultClientMessage());
            }
            userRepository.save(user);
        }
        selected.markPrimaryAdmin(true);
        userRepository.save(selected);
        tenant.setPrimaryAdminUserId(userId);
        tenantRepository.saveAndFlush(tenant);
        return details(tenantId);
    }

    @Transactional
    public TenantDetails updateUsers(long tenantId, List<UserConfigRequest> configs, boolean fromClientAdmin) {
        VideoOfferTenant tenant = requiredTenant(tenantId);
        Map<Long, UserConfigRequest> byId = new HashMap<>();
        if (configs != null) configs.forEach(item -> byId.put(item.bitrixUserId(), item));
        List<VideoOfferTenantUser> users = userRepository.findAllByTenantIdOrderByDisplayNameAsc(tenantId);
        long requestedAccessCount = users.stream()
                .filter(VideoOfferTenantUser::isActive)
                .filter(user -> {
                    UserConfigRequest request = byId.get(user.getBitrixUserId());
                    return request == null ? user.hasOfferAccess() : (request.offerAccess() || request.admin());
                })
                .count();
        if (requestedAccessCount > tenant.getSeatLimit()) {
            throw new IllegalArgumentException("Превышен лимит подключённых сотрудников: " + tenant.getSeatLimit());
        }

        for (VideoOfferTenantUser user : users) {
            UserConfigRequest request = byId.get(user.getBitrixUserId());
            if (request == null) continue;
            if (user.isPrimaryAdmin() && !request.offerAccess()) {
                throw new IllegalArgumentException("Главному администратору нельзя отключить доступ");
            }
            boolean admin = request.admin();
            if (fromClientAdmin && user.isPrimaryAdmin()) admin = true;
            boolean offerAccess = request.offerAccess() || admin || user.isPrimaryAdmin();
            user.configure(offerAccess, admin, request.defaultAccompanyingText(), request.defaultClientMessage());
            userRepository.save(user);
        }
        userRepository.flush();
        return details(tenantId);
    }

    @Transactional
    public TenantDetails resetUsage(long tenantId) {
        VideoOfferTenant tenant = requiredTenant(tenantId);
        tenant.resetOffersUsed();
        tenantRepository.save(tenant);
        for (VideoOfferTenantUser user : userRepository.findAllByTenantIdOrderByDisplayNameAsc(tenantId)) {
            user.resetOffersUsed();
            userRepository.save(user);
        }
        return details(tenantId);
    }

    @Transactional
    public void delete(long tenantId) {
        tenantRepository.delete(requiredTenant(tenantId));
    }

    public VideoOfferTenant requiredTenant(long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Клиент Video Offer не найден"));
    }

    private List<BitrixUserSnapshot> loadBitrixUsers(VideoOfferTenant tenant) {
        if (tenant.getWebhookUrl() == null || tenant.getWebhookUrl().isBlank()) {
            throw new IllegalArgumentException("Сначала укажите входящий webhook Bitrix24");
        }
        List<BitrixUserSnapshot> users = new ArrayList<>();
        int start = 0;
        while (start < 10_000) {
            Map<String, Object> response = webhookClient.call(tenant.getWebhookUrl(), "user.get", Map.of(
                    "FILTER", Map.of("USER_TYPE", "employee"),
                    "start", start));
            Object result = response.get("result");
            int pageSize = 0;
            if (result instanceof Collection<?> collection) {
                pageSize = collection.size();
                for (Object raw : collection) {
                    Map<String, Object> user = map(raw);
                    long id = positiveLong(first(user, "ID", "id"));
                    if (id <= 0) continue;
                    boolean active = booleanValue(first(user, "ACTIVE", "active"), true);
                    users.add(new BitrixUserSnapshot(id, joinName(user), stringOrNull(first(user, "EMAIL", "email")), active));
                }
            }
            if (pageSize < 50) break;
            start += 50;
        }
        return users;
    }

    private TenantSummary summary(VideoOfferTenant tenant) {
        long storage = accessService.currentStorageBytes(tenant.getId());
        return new TenantSummary(tenant.getId(), tenant.getName(), tenant.getPortalDomain(), tenant.getMemberId(),
                tenant.getStatus(), tenant.getPackageName(), tenant.getSeatLimit(),
                userRepository.countByTenantIdAndOfferAccessTrueAndActiveTrue(tenant.getId()),
                tenant.getOfferLimit(), tenant.getOffersUsed(), tenant.getDiskQuotaBytes(), storage,
                tenant.getAllowAnyEntity(), tenant.getPrimaryAdminUserId());
    }


    private String safeBitrixMessage(String value) {
        if (value == null || value.isBlank()) return "неизвестная ошибка";
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 240 ? compact.substring(0, 240) + "…" : compact;
    }

    private long quotaBytes(Double gb) {
        double value = gb == null || gb <= 0 ? 10.0 : gb;
        return Math.max(100L * 1024 * 1024, Math.round(value * 1024 * 1024 * 1024));
    }

    private int positive(Integer value, int fallback) { return value == null || value <= 0 ? Math.max(1, fallback) : value; }
    private Map<String, Object> map(Object value) { if (!(value instanceof Map<?, ?> raw)) return Map.of(); Map<String,Object> out=new LinkedHashMap<>(); raw.forEach((k,v)->out.put(String.valueOf(k),v)); return out; }
    private Object first(Map<String, Object> map, String... keys) { for (String key: keys) if (map.containsKey(key)) return map.get(key); return null; }
    private long positiveLong(Object value) { try { return value == null ? 0 : Math.max(0, Long.parseLong(String.valueOf(value))); } catch (Exception e) { return 0; } }
    private int collectionSize(Object value) { return value instanceof Collection<?> collection ? collection.size() : 0; }
    private boolean booleanValue(Object value, boolean fallback) { if (value == null) return fallback; if (value instanceof Boolean b) return b; String s=String.valueOf(value); return "Y".equalsIgnoreCase(s)||"true".equalsIgnoreCase(s)||"1".equals(s); }
    private String stringOrNull(Object value) { if (value == null) return null; String s=String.valueOf(value).trim(); return s.isEmpty()?null:s; }
    private String joinName(Map<String, Object> user) { StringBuilder b=new StringBuilder(); for (String key: List.of("NAME","SECOND_NAME","LAST_NAME")) { String s=stringOrNull(first(user,key,key.toLowerCase())); if(s!=null){if(!b.isEmpty())b.append(' '); b.append(s);}} long id=positiveLong(first(user,"ID","id")); return b.isEmpty()?"Сотрудник Bitrix24 #"+id:b.toString(); }

    public record CreateTenantRequest(String name, String portalDomain, String webhookUrl,
                                      String localClientId, String localClientSecret,
                                      String packageName, Integer seatLimit, Integer offerLimit,
                                      Double diskQuotaGb, Boolean allowAnyEntity) {}
    public record UpdateTenantRequest(String name, String portalDomain, String webhookUrl,
                                      String localClientId, String localClientSecret,
                                      TenantStatus status, String packageName,
                                      Integer seatLimit, Integer offerLimit, Double diskQuotaGb,
                                      Boolean allowAnyEntity) {}
    public record UserConfigRequest(long bitrixUserId, boolean offerAccess, boolean admin,
                                    String defaultAccompanyingText, String defaultClientMessage) {}
    public record ConnectionTest(boolean ok, String message, String webhookOwner, int usersFound) {}
    private record BitrixUserSnapshot(long id, String name, String email, boolean active) {}

    public record TenantSummary(Long id, String name, String portalDomain, String memberId,
                                TenantStatus status, String packageName, int seatLimit, long seatsUsed,
                                int offerLimit, long offersUsed, long diskQuotaBytes, long diskUsedBytes,
                                Boolean allowAnyEntity, Long primaryAdminUserId) {}

    public record TenantUserView(Long bitrixUserId, String displayName, String email, boolean active,
                                 boolean offerAccess, boolean admin, boolean primaryAdmin,
                                 String defaultAccompanyingText, String defaultClientMessage,
                                 long offersUsed) {
        static TenantUserView from(VideoOfferTenantUser user) {
            return new TenantUserView(user.getBitrixUserId(), user.getDisplayName(), user.getEmail(),
                    user.isActive(), user.hasOfferAccess(), user.isAdmin(), user.isPrimaryAdmin(),
                    user.getDefaultAccompanyingText(), user.getDefaultClientMessage(), user.getOffersUsed());
        }
    }

    public record TenantDetails(Long id, String name, String portalDomain, String memberId,
                                String webhookUrl, String localClientId, String localClientSecret,
                                TenantStatus status, String packageName,
                                int seatLimit, long seatsUsed, int offerLimit, long offersUsed,
                                long diskQuotaBytes, long diskUsedBytes, boolean allowAnyEntity,
                                Long primaryAdminUserId, String pageSettingsJson,
                                List<TenantUserView> users) {
        static TenantDetails from(VideoOfferTenant tenant, List<TenantUserView> users, long storage) {
            long seats = users.stream().filter(u -> u.active() && u.offerAccess()).count();
            return new TenantDetails(tenant.getId(), tenant.getName(), tenant.getPortalDomain(), tenant.getMemberId(),
                    tenant.getWebhookUrl(), tenant.getLocalClientId(), tenant.getLocalClientSecret(),
                    tenant.getStatus(), tenant.getPackageName(), tenant.getSeatLimit(), seats,
                    tenant.getOfferLimit(), tenant.getOffersUsed(), tenant.getDiskQuotaBytes(), storage,
                    tenant.allowAnyEntity(), tenant.getPrimaryAdminUserId(), tenant.getPageSettingsJson(), users);
        }
    }
}
