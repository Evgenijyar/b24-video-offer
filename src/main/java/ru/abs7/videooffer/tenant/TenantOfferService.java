package ru.abs7.videooffer.tenant;

import org.springframework.stereotype.Service;
import ru.abs7.videooffer.analytics.VideoOfferEventRepository;
import ru.abs7.videooffer.analytics.VideoOfferEventType;
import ru.abs7.videooffer.offer.CrmEntityType;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferRepository;
import ru.abs7.videooffer.offer.VideoOfferStatus;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TenantOfferService {
    private static final long TITLE_CACHE_MILLIS = 5 * 60 * 1000L;

    private final VideoOfferTenantRepository tenantRepository;
    private final VideoOfferRepository offerRepository;
    private final VideoOfferEventRepository eventRepository;
    private final VideoOfferTenantUserRepository tenantUserRepository;
    private final BitrixWebhookClient webhookClient;
    private final Map<String, CachedTitle> titleCache = new ConcurrentHashMap<>();

    public TenantOfferService(
            VideoOfferTenantRepository tenantRepository,
            VideoOfferRepository offerRepository,
            VideoOfferEventRepository eventRepository,
            VideoOfferTenantUserRepository tenantUserRepository,
            BitrixWebhookClient webhookClient) {
        this.tenantRepository = tenantRepository;
        this.offerRepository = offerRepository;
        this.eventRepository = eventRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.webhookClient = webhookClient;
    }

    public List<OfferView> activeOffers(long tenantId) {
        VideoOfferTenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Клиент Video Offer не найден"));
        List<VideoOffer> offers = offerRepository.findActiveReadyForTenant(
                tenantId,
                normalize(tenant.getMemberId()),
                VideoOfferStatus.READY,
                OffsetDateTime.now());
        if (offers.isEmpty()) return List.of();

        List<UUID> ids = offers.stream().map(VideoOffer::getId).toList();
        Set<UUID> started = new HashSet<>(eventRepository.findOfferIdsWithEvent(ids, VideoOfferEventType.VIDEO_STARTED));
        Map<Long, String> authorNames = new HashMap<>();
        for (VideoOfferTenantUser user : tenantUserRepository.findAllByTenantIdOrderByDisplayNameAsc(tenantId)) {
            if (user.getBitrixUserId() != null) {
                authorNames.put(user.getBitrixUserId(), user.getDisplayName());
            }
        }

        List<OfferView> result = new ArrayList<>(offers.size());
        for (VideoOffer offer : offers) {
            String title = resolveDocumentTitle(tenant, offer.getCrmEntityType(), offer.getCrmEntityId());
            boolean viewed = started.contains(offer.getId()) || offer.getViewGoalReachedAt() != null;
            result.add(new OfferView(
                    offer.getId(),
                    offer.getCrmEntityType().name(),
                    offer.getCrmEntityType().russianLabel(),
                    offer.getCrmEntityId(),
                    title,
                    authorName(offer.getBitrixUserId(), authorNames),
                    viewed,
                    offer.getCreatedAt(),
                    offer.getExpiresAt(),
                    documentUrl(tenant.getPortalDomain(), offer.getCrmEntityType(), offer.getCrmEntityId())));
        }
        return result;
    }

    private String resolveDocumentTitle(VideoOfferTenant tenant, CrmEntityType type, long entityId) {
        String key = tenant.getId() + ":" + type.name() + ":" + entityId;
        CachedTitle cached = titleCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMillis() < TITLE_CACHE_MILLIS) return cached.title();

        String fallback = type.russianLabel() + " №" + entityId;
        if (tenant.getWebhookUrl() == null || tenant.getWebhookUrl().isBlank()) return fallback;
        try {
            String method = switch (type) {
                case DEAL -> "crm.deal.get";
                case LEAD -> "crm.lead.get";
                case CONTACT -> "crm.contact.get";
            };
            Map<String, Object> response = webhookClient.call(tenant.getWebhookUrl(), method, Map.of("ID", entityId));
            Map<String, Object> item = map(response.get("result"));
            String title = switch (type) {
                case DEAL, LEAD -> normalize(string(first(item, "TITLE", "title")));
                case CONTACT -> joinName(item);
            };
            if (title == null || title.isBlank()) title = fallback;
            titleCache.put(key, new CachedTitle(title, now));
            return title;
        } catch (RuntimeException ignored) {
            return cached != null ? cached.title() : fallback;
        }
    }

    private String authorName(Long bitrixUserId, Map<Long, String> authorNames) {
        if (bitrixUserId == null || bitrixUserId <= 0) return "—";
        String name = normalize(authorNames.get(bitrixUserId));
        return name == null ? "Bitrix ID " + bitrixUserId : name;
    }

    private String documentUrl(String domain, CrmEntityType type, long entityId) {
        return "https://" + VideoOfferTenant.normalizeDomain(domain)
                + "/crm/" + type.bitrixApiName() + "/details/" + entityId + "/";
    }

    private String joinName(Map<String, Object> item) {
        StringJoiner joiner = new StringJoiner(" ");
        for (String key : List.of("NAME", "SECOND_NAME", "LAST_NAME")) {
            String value = normalize(string(first(item, key, key.toLowerCase(Locale.ROOT))));
            if (value != null) joiner.add(value);
        }
        String result = joiner.toString();
        return result.isBlank() ? null : result;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private String normalize(String value) { if (value == null) return null; String out=value.trim(); return out.isEmpty()?null:out; }

    private record CachedTitle(String title, long loadedAtMillis) {}

    public record OfferView(
            UUID offerId,
            String documentType,
            String documentTypeLabel,
            long documentId,
            String documentTitle,
            String authorName,
            boolean viewed,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            String documentUrl) {}
}
