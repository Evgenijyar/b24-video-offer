package ru.abs7.videooffer.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.videooffer.bitrix.BitrixPlacementContext;
import ru.abs7.videooffer.bitrix.BitrixRestClient;
import ru.abs7.videooffer.offer.CrmEntityType;
import ru.abs7.videooffer.offer.VideoOfferRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TenantAccessService {
    private static final Logger log = LoggerFactory.getLogger(TenantAccessService.class);
    public static final String VIDEO_URL_PLACEHOLDER = "{{VIDEO_URL}}";

    private final VideoOfferTenantRepository tenantRepository;
    private final VideoOfferTenantUserRepository userRepository;
    private final VideoOfferRepository offerRepository;
    private final BitrixRestClient restClient;
    private final BitrixWebhookClient webhookClient;

    public TenantAccessService(
            VideoOfferTenantRepository tenantRepository,
            VideoOfferTenantUserRepository userRepository,
            VideoOfferRepository offerRepository,
            BitrixRestClient restClient,
            BitrixWebhookClient webhookClient) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.offerRepository = offerRepository;
        this.restClient = restClient;
        this.webhookClient = webhookClient;
    }

    @Transactional
    public AccessDecision resolveDesktop(
            String memberId,
            String portalDomain,
            String currentAccessToken,
            CrmEntityType entityType,
            long entityId) {
        VideoOfferTenant tenant = locateAndBindTenant(memberId, portalDomain);
        if (tenant == null) {
            return AccessDecision.denied("PORTAL_NOT_REGISTERED",
                    "Этот портал Bitrix24 не подключён к Video Offer. Обратитесь к администратору сервиса.");
        }
        if (!tenant.isActive()) {
            return AccessDecision.denied("PORTAL_DISABLED",
                    "Доступ к Video Offer для компании временно отключён.");
        }

        CurrentBitrixUser current = resolveCurrentUser(portalDomain, currentAccessToken);
        VideoOfferTenantUser user = userRepository.findByTenantIdAndBitrixUserId(tenant.getId(), current.id()).orElse(null);
        if (user == null || !user.isActive() || !user.hasOfferAccess()) {
            return AccessDecision.denied("USER_NOT_ALLOWED", noUserAccessMessage(tenant));
        }
        user.synchronizeProfile(current.name(), current.email(), true);
        user.touch();
        userRepository.save(user);

        long responsibleId = resolveResponsibleWithToken(portalDomain, currentAccessToken, entityType, entityId);
        if (!tenant.allowAnyEntity() && responsibleId != current.id()) {
            return AccessDecision.denied("NOT_RESPONSIBLE",
                    "Нет прав для создания видеооффера в выбранном документе. Создать видеооффер может только ответственный сотрудник.");
        }

        return AccessDecision.allowed(tenant, user, responsibleId, current.id());
    }

    @Transactional
    public MobileAccessDecision resolveMobile(
            String memberId,
            String portalDomain,
            String currentAccessToken) {
        VideoOfferTenant tenant = locateAndBindTenant(memberId, portalDomain);
        if (tenant == null) {
            return MobileAccessDecision.denied("PORTAL_NOT_REGISTERED",
                    "Этот портал Bitrix24 не подключён к Video Offer. Обратитесь к администратору сервиса.");
        }
        if (!tenant.isActive()) {
            return MobileAccessDecision.denied("PORTAL_DISABLED", "Доступ к Video Offer для компании временно отключён.");
        }
        CurrentBitrixUser current = resolveCurrentUser(portalDomain, currentAccessToken);
        VideoOfferTenantUser user = userRepository.findByTenantIdAndBitrixUserId(tenant.getId(), current.id()).orElse(null);
        if (user == null || !user.isActive() || !user.hasOfferAccess()) {
            return MobileAccessDecision.denied("USER_NOT_ALLOWED", noUserAccessMessage(tenant));
        }
        user.synchronizeProfile(current.name(), current.email(), true);
        user.touch();
        userRepository.save(user);
        return MobileAccessDecision.allowed(tenant, user, current.id());
    }

    public EntityAccess checkKnownResponsible(
            Long tenantId,
            String memberId,
            Long bitrixUserId,
            Long responsibleId) {
        VideoOfferTenant tenant = requiredTenant(tenantId, memberId);
        VideoOfferTenantUser user = requiredUser(tenant.getId(), bitrixUserId);
        if (!tenant.isActive() || !user.isActive() || !user.hasOfferAccess()) {
            return new EntityAccess(false, responsibleId, "У вас нет доступа к приложению.");
        }
        if (responsibleId == null || responsibleId <= 0) {
            return new EntityAccess(false, responsibleId, "Не удалось определить ответственного по документу.");
        }
        boolean allowed = tenant.allowAnyEntity() || responsibleId.equals(bitrixUserId);
        return new EntityAccess(allowed, responsibleId,
                allowed ? null : "Вы не являетесь ответственным по данному документу. Выберите другой документ.");
    }

    public EntityAccess checkSelectedEntity(
            Long tenantId,
            String memberId,
            Long bitrixUserId,
            CrmEntityType entityType,
            long entityId) {
        VideoOfferTenant tenant = requiredTenant(tenantId, memberId);
        VideoOfferTenantUser user = requiredUser(tenant.getId(), bitrixUserId);
        if (!tenant.isActive() || !user.isActive() || !user.hasOfferAccess()) {
            return new EntityAccess(false, null, "У вас нет доступа к приложению.");
        }
        long responsibleId = resolveResponsibleServerSide(tenant, memberId, entityType, entityId);
        boolean allowed = tenant.allowAnyEntity() || responsibleId == bitrixUserId;
        return new EntityAccess(allowed, responsibleId,
                allowed ? null : "Вы не являетесь ответственным по данному документу. Выберите другой документ.");
    }

    public EntityAccess assertContextCanCreate(BitrixPlacementContext context) {
        if (context.tenantId() == null || context.bitrixUserId() == null) {
            throw new IllegalArgumentException("Открыта устаревшая форма Video Offer. Закройте её и откройте приложение заново");
        }
        EntityAccess access = checkSelectedEntity(
                context.tenantId(), context.memberId(), context.bitrixUserId(), context.entityType(), context.entityId());
        if (!access.allowed()) throw new IllegalArgumentException(access.message());
        return access;
    }

    @Transactional
    public UsageSnapshot consumeOffer(BitrixPlacementContext context) {
        assertContextCanCreate(context);
        VideoOfferTenant tenant = tenantRepository.findByIdForUpdate(context.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Компания Video Offer не найдена"));
        VideoOfferTenantUser user = userRepository.findForUpdate(tenant.getId(), context.bitrixUserId())
                .orElseThrow(() -> new IllegalArgumentException("Сотрудник Video Offer не найден"));
        long used = tenant.getOffersUsed() == null ? 0 : tenant.getOffersUsed();
        if (used >= tenant.getOfferLimit()) {
            throw new IllegalArgumentException("Лимит видеоофферов исчерпан. Обратитесь к администратору компании");
        }
        tenant.incrementOffersUsed();
        user.incrementOffersUsed();
        tenantRepository.save(tenant);
        userRepository.save(user);
        return usage(tenant, user);
    }

    @Transactional
    public void releaseConsumedOffer(BitrixPlacementContext context) {
        if (context == null) return;
        releaseConsumedOffer(context.tenantId(), context.bitrixUserId());
    }

    @Transactional
    public void releaseConsumedOffer(Long tenantId, Long bitrixUserId) {
        if (tenantId == null || bitrixUserId == null) return;
        VideoOfferTenant tenant = tenantRepository.findByIdForUpdate(tenantId).orElse(null);
        VideoOfferTenantUser user = userRepository.findForUpdate(tenantId, bitrixUserId).orElse(null);
        if (tenant == null || user == null) return;
        tenant.decrementOffersUsed();
        user.decrementOffersUsed();
        tenantRepository.save(tenant);
        userRepository.save(user);
    }

    @Transactional
    public void ensureStorageAvailable(Long tenantId, long additionalBytes) {
        if (tenantId == null || tenantId <= 0 || additionalBytes <= 0) return;
        VideoOfferTenant tenant = tenantRepository.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Компания Video Offer не найдена"));
        long used = currentStorageBytes(tenantId);
        if (used + additionalBytes > tenant.getDiskQuotaBytes()) {
            throw new IllegalArgumentException("Недостаточно места в хранилище компании. Использовано "
                    + humanBytes(used) + " из " + humanBytes(tenant.getDiskQuotaBytes()));
        }
    }

    public long currentStorageBytes(Long tenantId) {
        if (tenantId == null) return 0;
        var status = ru.abs7.videooffer.offer.VideoOfferStatus.READY;
        Long tenantValue = offerRepository.sumReadyStorageByTenantId(tenantId, status);
        long total = tenantValue == null ? 0L : tenantValue;
        VideoOfferTenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant != null && tenant.getMemberId() != null && !tenant.getMemberId().isBlank()) {
            Long legacyValue = offerRepository.sumLegacyReadyStorageByMemberId(tenant.getMemberId(), status);
            total += legacyValue == null ? 0L : legacyValue;
        }
        return total;
    }

    public UserDefaults defaults(BitrixPlacementContext context) {
        if (context == null) return new UserDefaults(null, defaultClientMessage());
        return defaultsForUser(context.tenantId(), context.bitrixUserId());
    }

    public UserDefaults defaultsForUser(Long tenantId, Long bitrixUserId) {
        if (tenantId == null || bitrixUserId == null) {
            return new UserDefaults(null, defaultClientMessage());
        }
        VideoOfferTenantUser user = userRepository.findByTenantIdAndBitrixUserId(tenantId, bitrixUserId).orElse(null);
        if (user == null) return new UserDefaults(null, defaultClientMessage());
        String configuredMessage = normalize(user.getDefaultClientMessage());
        return new UserDefaults(
                normalize(user.getDefaultAccompanyingText()),
                configuredMessage == null ? defaultClientMessage() : user.getDefaultClientMessage());
    }

    public VideoOfferTenant requiredTenant(Long tenantId, String memberId) {
        if (tenantId == null) throw new IllegalArgumentException("Компания Video Offer не определена");
        VideoOfferTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Компания Video Offer не найдена"));
        if (memberId != null && tenant.getMemberId() != null && !memberId.equals(tenant.getMemberId())) {
            throw new IllegalArgumentException("Контекст Bitrix24 относится к другой компании");
        }
        return tenant;
    }

    public VideoOfferTenantUser requiredUser(Long tenantId, Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("Пользователь Bitrix24 не определён");
        return userRepository.findByTenantIdAndBitrixUserId(tenantId, userId)
                .orElseThrow(() -> new IllegalArgumentException("У вас нет доступа к приложению"));
    }

    public UsageSnapshot usageFor(Long tenantId, Long userId) {
        VideoOfferTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Компания Video Offer не найдена"));
        VideoOfferTenantUser user = requiredUser(tenantId, userId);
        return usage(tenant, user);
    }

    private UsageSnapshot usage(VideoOfferTenant tenant, VideoOfferTenantUser user) {
        long storage = currentStorageBytes(tenant.getId());
        return new UsageSnapshot(
                tenant.getOfferLimit(), tenant.getOffersUsed(), Math.max(0, tenant.getOfferLimit() - tenant.getOffersUsed()),
                tenant.getSeatLimit(), userRepository.countByTenantIdAndOfferAccessTrueAndActiveTrue(tenant.getId()),
                tenant.getDiskQuotaBytes(), storage, Math.max(0, tenant.getDiskQuotaBytes() - storage),
                user.getOffersUsed());
    }

    private VideoOfferTenant locateAndBindTenant(String memberId, String portalDomain) {
        String domain = VideoOfferTenant.normalizeDomain(portalDomain);
        String normalizedMemberId = memberId == null ? null : memberId.trim();
        VideoOfferTenant tenant = null;
        if (normalizedMemberId != null && !normalizedMemberId.isBlank()) {
            tenant = tenantRepository.findByMemberId(normalizedMemberId).orElse(null);
            if (tenant != null && !domain.equalsIgnoreCase(tenant.getPortalDomain())) {
                throw new IllegalArgumentException("member_id Bitrix24 привязан к другому порталу Video Offer");
            }
        }
        if (tenant == null) {
            tenant = tenantRepository.findByPortalDomainIgnoreCase(domain).orElse(null);
        }
        if (tenant == null) return null;
        if (tenant.getMemberId() != null && !tenant.getMemberId().isBlank()
                && normalizedMemberId != null && !normalizedMemberId.isBlank()
                && !tenant.getMemberId().equals(normalizedMemberId)) {
            throw new IllegalArgumentException("Портал Bitrix24 передал другой member_id, чем зарегистрирован в Video Offer");
        }
        if ((tenant.getMemberId() == null || tenant.getMemberId().isBlank())
                && normalizedMemberId != null && !normalizedMemberId.isBlank()) {
            tenant.bindMemberId(normalizedMemberId);
            tenant = tenantRepository.saveAndFlush(tenant);
            log.info("Video Offer tenant bound to Bitrix member_id: tenantId={}, domain={}, memberId={}",
                    tenant.getId(), tenant.getPortalDomain(), normalizedMemberId);
        }
        return tenant;
    }

    private CurrentBitrixUser resolveCurrentUser(String domain, String accessToken) {
        Map<String, Object> response = restClient.callWithAccessToken(domain, accessToken, "user.current", Map.of());
        Map<String, Object> user = map(response.get("result"));
        long id = positiveLong(first(user, "ID", "id"));
        if (id <= 0) throw new IllegalStateException("Bitrix24 не вернул ID текущего пользователя");
        String name = joinName(
                string(first(user, "NAME", "name")),
                string(first(user, "SECOND_NAME", "secondName")),
                string(first(user, "LAST_NAME", "lastName")));
        return new CurrentBitrixUser(id, name.isBlank() ? "Сотрудник Bitrix24 #" + id : name,
                normalize(string(first(user, "EMAIL", "email"))));
    }

    private long resolveResponsibleWithToken(String domain, String accessToken, CrmEntityType type, long entityId) {
        Map<String, Object> response = restClient.callWithAccessToken(domain, accessToken, "crm.item.get", Map.of(
                "entityTypeId", type.bitrixEntityTypeId(), "id", entityId, "useOriginalUfNames", "N"));
        return responsibleFromResponse(response, type, entityId);
    }

    private long resolveResponsibleServerSide(VideoOfferTenant tenant, String memberId, CrmEntityType type, long entityId) {
        Map<String, Object> response;
        if (tenant.getWebhookUrl() != null && !tenant.getWebhookUrl().isBlank()) {
            response = webhookClient.call(tenant.getWebhookUrl(), "crm.item.get", Map.of(
                    "entityTypeId", type.bitrixEntityTypeId(), "id", entityId, "useOriginalUfNames", "N"));
        } else {
            response = restClient.call(memberId, "crm.item.get", Map.of(
                    "entityTypeId", type.bitrixEntityTypeId(), "id", entityId, "useOriginalUfNames", "N"));
        }
        return responsibleFromResponse(response, type, entityId);
    }

    private long responsibleFromResponse(Map<String, Object> response, CrmEntityType type, long entityId) {
        Map<String, Object> result = map(response.get("result"));
        Map<String, Object> item = map(result.get("item"));
        long responsible = positiveLong(first(item, "assignedById", "ASSIGNED_BY_ID"));
        if (responsible <= 0) throw new IllegalStateException("Bitrix24 не вернул ответственного для " + type + " №" + entityId);
        return responsible;
    }

    private String noUserAccessMessage(VideoOfferTenant tenant) {
        String admin = "администратору компании";
        if (tenant.getPrimaryAdminUserId() != null) {
            VideoOfferTenantUser user = userRepository.findByTenantIdAndBitrixUserId(tenant.getId(), tenant.getPrimaryAdminUserId()).orElse(null);
            if (user != null) admin = user.getDisplayName();
        }
        return "У вас нет доступа к приложению. Обратитесь к " + admin + ".";
    }

    public String defaultClientMessage() {
        return "В продолжение нашего разговора подготовил для вас короткую видеопрезентацию.\n\n"
                + "Посмотреть можно по ссылке:\n" + VIDEO_URL_PLACEHOLDER;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private long positiveLong(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try { return value == null ? 0 : Math.max(0, Long.parseLong(String.valueOf(value))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String joinName(String... values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized == null) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(normalized);
        }
        return out.toString();
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private String normalize(String value) { if (value == null) return null; String v = value.trim(); return v.isEmpty() ? null : v; }
    private String humanBytes(long value) {
        double mb = value / 1024.0 / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f МБ", mb);
        return String.format(java.util.Locale.ROOT, "%.2f ГБ", mb / 1024.0);
    }

    public record CurrentBitrixUser(long id, String name, String email) {}
    public record EntityAccess(boolean allowed, Long responsibleId, String message) {}
    public record UserDefaults(String accompanyingText, String clientMessage) {}
    public record UsageSnapshot(
            long offerLimit, long offersUsed, long offersRemaining,
            long seatLimit, long seatsUsed,
            long diskQuotaBytes, long diskUsedBytes, long diskRemainingBytes,
            long employeeOffersUsed) {}

    public record AccessDecision(
            boolean allowed, String code, String message,
            Long tenantId, String tenantName, String memberId,
            Long bitrixUserId, String userName, boolean admin, boolean primaryAdmin,
            Long responsibleId, boolean allowAnyEntity) {
        static AccessDecision denied(String code, String message) {
            return new AccessDecision(false, code, message, null, null, null, null, null, false, false, null, false);
        }
        static AccessDecision allowed(VideoOfferTenant tenant, VideoOfferTenantUser user, Long responsibleId, Long currentId) {
            return new AccessDecision(true, "OK", null, tenant.getId(), tenant.getName(), tenant.getMemberId(), currentId,
                    user.getDisplayName(), user.isAdmin(), user.isPrimaryAdmin(), responsibleId, tenant.allowAnyEntity());
        }
    }

    public record MobileAccessDecision(
            boolean allowed, String code, String message,
            Long tenantId, String tenantName, String memberId,
            Long bitrixUserId, String userName, boolean admin, boolean primaryAdmin) {
        static MobileAccessDecision denied(String code, String message) {
            return new MobileAccessDecision(false, code, message, null, null, null, null, null, false, false);
        }
        static MobileAccessDecision allowed(VideoOfferTenant tenant, VideoOfferTenantUser user, Long currentId) {
            return new MobileAccessDecision(true, "OK", null, tenant.getId(), tenant.getName(), tenant.getMemberId(), currentId,
                    user.getDisplayName(), user.isAdmin(), user.isPrimaryAdmin());
        }
    }
}
