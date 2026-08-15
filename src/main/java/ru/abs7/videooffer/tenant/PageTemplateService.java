package ru.abs7.videooffer.tenant;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferRepository;

import java.io.IOException;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class PageTemplateService {
    private static final Logger log = LoggerFactory.getLogger(PageTemplateService.class);
    private static final int TEMPLATE_VERSION = 2;
    private static final int MAX_BLOCKS_PER_TYPE = 5;
    private static final int MAX_BLOCKS_TOTAL = 40;
    private static final long IMAGE_MAX = 10L * 1024 * 1024;
    private static final long VIDEO_MAX = 100L * 1024 * 1024;
    private static final long FILE_MAX = 25L * 1024 * 1024;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Set<String> TYPES = Set.of("VIDEO", "TEXT", "IMAGE", "FILE", "BUTTON", "ICON_TEXT", "DIVIDER", "EMBED");
    private static final Set<String> VISIBILITY = Set.of("ALL", "DESKTOP", "MOBILE");
    private static final Set<String> ALIGNMENT = Set.of("LEFT", "CENTER", "RIGHT");
    private static final Set<String> FONT_FAMILIES = Set.of("DEFAULT", "ARIAL", "VERDANA", "GEORGIA", "TIMES_NEW_ROMAN", "TREBUCHET_MS", "COURIER_NEW");
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm");
    private static final Set<String> FILE_EXT = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "odt", "ods", "odp");

    private final ObjectMapper mapper;
    private final VideoOfferTenantRepository tenantRepository;
    private final VideoOfferRepository offerRepository;
    private final TenantStorageQuotaService storageQuotaService;
    private final Path root;

    public PageTemplateService(
            ObjectMapper mapper,
            VideoOfferTenantRepository tenantRepository,
            VideoOfferRepository offerRepository,
            TenantStorageQuotaService storageQuotaService,
            @Value("${app.page-builder.storage-dir:./data/page-builder}") String storageDir) {
        this.mapper = mapper;
        this.tenantRepository = tenantRepository;
        this.offerRepository = offerRepository;
        this.storageQuotaService = storageQuotaService;
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve("assets"));
            Files.createDirectories(root.resolve("drafts"));
            Files.createDirectories(root.resolve("attachments"));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось подготовить хранилище конструктора страницы", e);
        }
    }

    public PageTemplateView template(long tenantId) {
        VideoOfferTenant tenant = requiredTenant(tenantId);
        return parseOrDefault(tenant.getPageSettingsJson(), tenant.getName());
    }

    @Transactional
    public PageTemplateView saveTemplate(long tenantId, PageTemplateView request) {
        VideoOfferTenant tenant = requiredTenant(tenantId);
        PageTemplateView normalized = validateAndNormalize(request, tenantId, tenant.getName());
        tenant.setPageSettingsJson(write(normalized));
        tenantRepository.saveAndFlush(tenant);
        return normalized;
    }

    @Transactional
    public void ensureDefault(VideoOfferTenant tenant) {
        if (tenant == null || tenant.getPageSettingsJson() != null && !tenant.getPageSettingsJson().isBlank()) return;
        tenant.setPageSettingsJson(write(defaultTemplate(tenant.getName())));
        tenantRepository.saveAndFlush(tenant);
    }

    @Transactional
    public OfferPagePrepared prepareOfferPage(
            Long tenantId,
            Long userId,
            UUID offerId,
            String accompanyingText,
            Map<String, String> textValues,
            Map<String, String> fileDraftIds) throws IOException {
        VideoOfferTenant tenant = tenantId == null || tenantId <= 0 ? null : requiredTenant(tenantId);
        PageTemplateView template = tenant == null
                ? defaultTemplate("Video Offer")
                : parseOrDefault(tenant.getPageSettingsJson(), tenant.getName());
        Map<String, String> inputTexts = textValues == null ? Map.of() : textValues;
        Map<String, String> inputFiles = fileDraftIds == null ? Map.of() : fileDraftIds;
        Map<String, String> finalTexts = new LinkedHashMap<>();
        Map<String, OfferAttachmentView> finalFiles = new LinkedHashMap<>();

        if (tenantId != null && userId != null) {
            long attachmentBytes = requiredDraftBytes(template, tenantId, userId, inputFiles);
            if (attachmentBytes > 0) storageQuotaService.ensureAvailable(tenantId, attachmentBytes);
        }

        try {
            for (PageBlockView block : template.blocks()) {
                Map<String, Object> config = block.config();
                if ("TEXT".equals(block.type()) && "MANAGER".equals(string(config.get("mode"), "STATIC"))) {
                    String value = "accompanyingText".equals(string(config.get("fieldKey"), null))
                            ? trimToNull(accompanyingText)
                            : trimToNull(inputTexts.get(block.id()));
                    if (bool(config.get("required"), false) && value == null) {
                        throw new IllegalArgumentException("Заполните поле «" + string(config.get("label"), "Текст") + "»");
                    }
                    if (value != null) finalTexts.put(block.id(), limit(value, 20_000));
                }
                if ("FILE".equals(block.type()) && "MANAGER".equals(string(config.get("mode"), "STATIC"))) {
                    String draftId = trimToNull(inputFiles.get(block.id()));
                    if (draftId == null) {
                        if (bool(config.get("required"), false)) {
                            throw new IllegalArgumentException("Прикрепите файл «" + string(config.get("label"), "Файл") + "»");
                        }
                        continue;
                    }
                    if (tenantId == null || userId == null) throw new IllegalArgumentException("Файл доступен только для офферов компании Bitrix24");
                    finalFiles.put(block.id(), claimDraft(tenantId, userId, offerId, block.id(), draftId));
                }
            }
            OfferPageContent content = new OfferPageContent(finalTexts, finalFiles);
            return new OfferPagePrepared(write(template), write(content));
        } catch (IOException | RuntimeException error) {
            deleteOfferFiles(tenantId, offerId);
            throw error;
        }
    }

    public PageTemplateView templateForOffer(VideoOffer offer) {
        if (offer.getPageTemplateJson() != null && !offer.getPageTemplateJson().isBlank()) {
            return parseOrDefault(offer.getPageTemplateJson(), companyName(offer.getTenantId()));
        }
        return offer.getTenantId() == null
                ? defaultTemplate("Video Offer")
                : template(offer.getTenantId());
    }

    public OfferPageContent contentForOffer(VideoOffer offer) {
        if (offer.getPageContentJson() == null || offer.getPageContentJson().isBlank()) return OfferPageContent.empty();
        try {
            return mapper.readValue(offer.getPageContentJson(), OfferPageContent.class);
        } catch (Exception error) {
            log.warn("Failed to parse offer page content: offerId={}, error={}", offer.getId(), error.getMessage());
            return OfferPageContent.empty();
        }
    }

    @Transactional
    public AssetUploadResponse uploadTemplateAsset(long tenantId, String kind, MultipartFile file) throws IOException {
        requiredTenant(tenantId);
        AssetKind assetKind = AssetKind.from(kind);
        validateFile(file, assetKind.allowedExtensions, assetKind.maxBytes, assetKind.label);
        storageQuotaService.ensureAvailable(tenantId, file.getSize());
        String extension = extension(file.getOriginalFilename());
        String token = UUID.randomUUID().toString().replace("-", "");
        Path dir = root.resolve("assets").resolve(Long.toString(tenantId));
        Files.createDirectories(dir);
        Path target = dir.resolve(token + "." + extension).normalize();
        requireInside(target, dir);
        file.transferTo(target);
        return new AssetUploadResponse(
                token,
                "/page-assets/" + tenantId + "/" + token,
                safeOriginalName(file.getOriginalFilename()),
                Files.size(target),
                probe(target, file.getContentType()),
                assetKind.name());
    }

    public DraftUploadResponse uploadDraftFile(long tenantId, long userId, String blockId, MultipartFile file) throws IOException {
        PageTemplateView template = template(tenantId);
        PageBlockView block = template.blocks().stream()
                .filter(item -> item.id().equals(blockId) && "FILE".equals(item.type())
                        && "MANAGER".equals(string(item.config().get("mode"), "STATIC")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Поле файла не найдено в шаблоне страницы"));
        validateFile(file, FILE_EXT, FILE_MAX, "файл");
        String draftId = UUID.randomUUID().toString().replace("-", "");
        Path dir = draftDirectory(tenantId, userId);
        Files.createDirectories(dir);
        Path data = dir.resolve(draftId + ".data");
        Path meta = dir.resolve(draftId + ".json");
        file.transferTo(data);
        DraftMeta draftMeta = new DraftMeta(
                draftId,
                block.id(),
                safeOriginalName(file.getOriginalFilename()),
                probe(data, file.getContentType()),
                Files.size(data),
                Instant.now().toString());
        Files.writeString(meta, write(draftMeta), StandardOpenOption.CREATE_NEW);
        return new DraftUploadResponse(draftId, draftMeta.fileName(), draftMeta.contentType(), draftMeta.size());
    }

    public AssetFile resolveTemplateAsset(long tenantId, String token) throws IOException {
        if (!SAFE_ID.matcher(token == null ? "" : token).matches()) throw new IllegalArgumentException("Некорректный файл");
        Path dir = root.resolve("assets").resolve(Long.toString(tenantId));
        if (!Files.isDirectory(dir)) throw new NoSuchElementException("Файл не найден");
        try (Stream<Path> stream = Files.list(dir)) {
            Path path = stream.filter(Files::isRegularFile)
                    .filter(item -> item.getFileName().toString().startsWith(token + "."))
                    .findFirst().orElseThrow(() -> new NoSuchElementException("Файл не найден"));
            return new AssetFile(path, probe(path, null), path.getFileName().toString());
        }
    }

    public AssetFile resolveOfferAttachment(VideoOffer offer, String attachmentId) throws IOException {
        if (!SAFE_ID.matcher(attachmentId == null ? "" : attachmentId).matches()) throw new IllegalArgumentException("Некорректный файл");
        OfferPageContent content = contentForOffer(offer);
        OfferAttachmentView attachment = content.files().values().stream()
                .filter(item -> attachmentId.equals(item.id()))
                .findFirst().orElseThrow(() -> new NoSuchElementException("Файл не найден"));
        Path dir = attachmentDirectory(offer.getTenantId(), offer.getId());
        Path path = dir.resolve(attachment.storedName()).normalize();
        requireInside(path, dir);
        if (!Files.isRegularFile(path)) throw new NoSuchElementException("Файл не найден");
        return new AssetFile(path, attachment.contentType(), attachment.fileName());
    }

    public void deleteOfferFiles(VideoOffer offer) {
        if (offer == null || offer.getTenantId() == null) return;
        deleteOfferFiles(offer.getTenantId(), offer.getId());
    }

    public void deleteOfferFiles(Long tenantId, UUID offerId) {
        if (tenantId == null || offerId == null) return;
        deleteTreeQuietly(attachmentDirectory(tenantId, offerId));
    }

    public void deleteTenantFiles(long tenantId) {
        deleteTreeQuietly(root.resolve("assets").resolve(Long.toString(tenantId)));
        deleteTreeQuietly(root.resolve("drafts").resolve(Long.toString(tenantId)));
        deleteTreeQuietly(root.resolve("attachments").resolve(Long.toString(tenantId)));
    }

    @Scheduled(cron = "0 21 3 * * *")
    public void cleanupStaleDrafts() {
        Path drafts = root.resolve("drafts");
        if (!Files.isDirectory(drafts)) return;
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        try (Stream<Path> paths = Files.walk(drafts)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) Files.deleteIfExists(path);
                } catch (IOException ignored) {}
            });
        } catch (IOException error) {
            log.warn("Failed to cleanup stale page-builder drafts: {}", error.getMessage());
        }
    }

    @Scheduled(cron = "0 38 3 * * *")
    public void cleanupOrphanedTemplateAssets() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        for (VideoOfferTenant tenant : tenantRepository.findAll()) {
            long tenantId = tenant.getId();
            Set<String> referenced = referencedAssetTokens(parseOrDefault(tenant.getPageSettingsJson(), tenant.getName()), tenantId);
            for (VideoOffer offer : offerRepository.findAllByTenantId(tenantId)) {
                String snapshot = offer.getPageTemplateJson();
                if (snapshot == null || snapshot.isBlank()) continue;
                try {
                    referenced.addAll(referencedAssetTokens(mapper.readValue(snapshot, PageTemplateView.class), tenantId));
                } catch (Exception error) {
                    log.warn("Failed to inspect page asset references: offerId={}, error={}", offer.getId(), error.getMessage());
                }
            }
            Path dir = root.resolve("assets").resolve(Long.toString(tenantId));
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile).forEach(path -> {
                    String fileName = path.getFileName().toString();
                    int dot = fileName.indexOf('.');
                    String token = dot > 0 ? fileName.substring(0, dot) : fileName;
                    if (referenced.contains(token)) return;
                    try {
                        if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) Files.deleteIfExists(path);
                    } catch (IOException ignored) {}
                });
            } catch (IOException error) {
                log.warn("Failed to cleanup orphaned page assets: tenantId={}, error={}", tenantId, error.getMessage());
            }
        }
    }

    private long requiredDraftBytes(PageTemplateView template, long tenantId, long userId, Map<String, String> inputFiles) throws IOException {
        long total = 0L;
        for (PageBlockView block : template.blocks()) {
            if (!"FILE".equals(block.type()) || !"MANAGER".equals(string(block.config().get("mode"), "STATIC"))) continue;
            String draftId = trimToNull(inputFiles.get(block.id()));
            if (draftId == null) continue;
            DraftMeta meta = readDraftMeta(tenantId, userId, block.id(), draftId);
            try {
                total = Math.addExact(total, Math.max(0L, meta.size()));
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException("Суммарный размер вложений слишком большой");
            }
        }
        return total;
    }

    private DraftMeta readDraftMeta(long tenantId, long userId, String blockId, String draftId) throws IOException {
        if (!SAFE_ID.matcher(draftId == null ? "" : draftId).matches()) {
            throw new IllegalArgumentException("Некорректный идентификатор загруженного файла");
        }
        Path dir = draftDirectory(tenantId, userId);
        Path data = dir.resolve(draftId + ".data").normalize();
        Path meta = dir.resolve(draftId + ".json").normalize();
        requireInside(data, dir);
        requireInside(meta, dir);
        if (!Files.isRegularFile(data) || !Files.isRegularFile(meta)) {
            throw new IllegalArgumentException("Загруженный файл не найден. Прикрепите его ещё раз");
        }
        DraftMeta draftMeta = mapper.readValue(Files.readString(meta), DraftMeta.class);
        if (!draftId.equals(draftMeta.id()) || !blockId.equals(draftMeta.blockId())) {
            throw new IllegalArgumentException("Файл относится к другому полю страницы");
        }
        long actual = Files.size(data);
        if (actual != draftMeta.size() || actual <= 0 || actual > FILE_MAX) {
            throw new IllegalArgumentException("Загруженный файл повреждён или имеет недопустимый размер");
        }
        return draftMeta;
    }

    private Set<String> referencedAssetTokens(PageTemplateView template, long tenantId) {
        Set<String> tokens = new HashSet<>();
        if (template == null || template.blocks() == null) return tokens;
        String prefix = "/page-assets/" + tenantId + "/";
        for (PageBlockView block : template.blocks()) {
            if (block == null || block.config() == null) continue;
            for (String key : List.of("logoUrl", "assetUrl")) {
                String url = trimToNull(string(block.config().get(key), null));
                if (url != null && url.startsWith(prefix)) {
                    String token = url.substring(prefix.length());
                    if (SAFE_ID.matcher(token).matches()) tokens.add(token);
                }
            }
        }
        return tokens;
    }


    private OfferAttachmentView claimDraft(long tenantId, long userId, UUID offerId, String blockId, String draftId) throws IOException {
        DraftMeta draftMeta = readDraftMeta(tenantId, userId, blockId, draftId);
        Path dir = draftDirectory(tenantId, userId);
        Path data = dir.resolve(draftId + ".data").normalize();
        Path meta = dir.resolve(draftId + ".json").normalize();
        String attachmentId = UUID.randomUUID().toString().replace("-", "");
        String extension = extension(draftMeta.fileName());
        String storedName = attachmentId + (extension.isBlank() ? "" : "." + extension);
        Path targetDir = attachmentDirectory(tenantId, offerId);
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(storedName).normalize();
        requireInside(target, targetDir);
        try {
            Files.move(data, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(data, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(meta);
        return new OfferAttachmentView(attachmentId, draftMeta.fileName(), draftMeta.contentType(), Files.size(target), storedName);
    }

    private PageTemplateView parseOrDefault(String json, String companyName) {
        if (json == null || json.isBlank()) return defaultTemplate(companyName);
        try {
            PageTemplateView parsed = mapper.readValue(json, PageTemplateView.class);
            return validateAndNormalize(parsed, null, companyName);
        } catch (Exception error) {
            log.warn("Invalid page template JSON, using default: {}", error.getMessage());
            return defaultTemplate(companyName);
        }
    }

    private PageTemplateView validateAndNormalize(PageTemplateView request, Long tenantId, String companyName) {
        if (request == null || request.blocks() == null) throw new IllegalArgumentException("Шаблон страницы пуст");
        if (request.blocks().isEmpty()) throw new IllegalArgumentException("Добавьте хотя бы один блок страницы");

        List<PageBlockView> sourceBlocks = new ArrayList<>();
        for (PageBlockView raw : request.blocks()) {
            if (raw == null) continue;
            if ("HEADER".equalsIgnoreCase(string(raw.type(), ""))) sourceBlocks.addAll(migrateLegacyHeader(raw, companyName));
            else sourceBlocks.add(raw);
        }
        if (sourceBlocks.isEmpty()) throw new IllegalArgumentException("Добавьте хотя бы один блок страницы");
        if (sourceBlocks.size() > MAX_BLOCKS_TOTAL) throw new IllegalArgumentException("Слишком много блоков на странице");

        Set<String> ids = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> rowUsage = new LinkedHashMap<>();
        List<PageBlockView> blocks = new ArrayList<>();
        int mainVideos = 0;
        int generatedRow = 0;

        for (PageBlockView raw : sourceBlocks) {
            String id = string(raw.id(), null);
            if (id == null || !SAFE_ID.matcher(id).matches() || !ids.add(id)) throw new IllegalArgumentException("Некорректный идентификатор блока");
            String type = string(raw.type(), "").toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) throw new IllegalArgumentException("Неизвестный тип блока: " + type);
            int count = counts.merge(type, 1, Integer::sum);
            if (count > MAX_BLOCKS_PER_TYPE) throw new IllegalArgumentException("Блок «" + type + "» можно добавить не более пяти раз");

            String visibility = string(raw.visibility(), "ALL").toUpperCase(Locale.ROOT);
            if (!VISIBILITY.contains(visibility)) visibility = "ALL";
            Map<String, Object> config = normalizeConfig(type, raw.config(), tenantId, companyName);

            String rowId = normalizeRowId(raw.rowId(), id, generatedRow++);
            int span = Optional.ofNullable(raw.span()).map(value -> Math.max(3, Math.min(12, value))).orElse(12);
            int used = rowUsage.getOrDefault(rowId, 0);
            if (used + span > 12) {
                rowId = generatedRowId(id, generatedRow++);
                used = 0;
            }
            rowUsage.put(rowId, used + span);

            if ("VIDEO".equals(type) && "MAIN".equals(config.get("source"))) mainVideos++;
            blocks.add(new PageBlockView(id, type, visibility, rowId, span, config));
        }
        if (mainVideos != 1) throw new IllegalArgumentException("На странице должен быть ровно один блок с основным видеооффером");
        return new PageTemplateView(TEMPLATE_VERSION, blocks);
    }

    private List<PageBlockView> migrateLegacyHeader(PageBlockView header, String companyName) {
        Map<String, Object> c = header.config() == null ? Map.of() : header.config();
        String visibility = string(header.visibility(), "ALL").toUpperCase(Locale.ROOT);
        if (!VISIBILITY.contains(visibility)) visibility = "ALL";
        String base = string(header.id(), "legacy-header").replaceAll("[^A-Za-z0-9_-]", "-");
        if (base.length() > 70) base = base.substring(0, 70);
        String rowId = generatedRowId(base, Math.abs(base.hashCode()));
        boolean hasLogo = trimToNull(string(c.get("logoUrl"), null)) != null;
        String company = trimToNull(string(c.get("companyName"), companyName));
        String phone = trimToNull(string(c.get("phoneText"), null));
        int parts = (hasLogo ? 1 : 0) + (company != null ? 1 : 0) + (phone != null ? 1 : 0);
        if (parts == 0) return List.of();

        List<PageBlockView> out = new ArrayList<>();
        if (hasLogo) {
            int span = parts == 1 ? 12 : parts == 2 ? 4 : 3;
            out.add(new PageBlockView(base + "-logo", "IMAGE", visibility, rowId, span, mapOf(
                    "assetUrl", c.get("logoUrl"), "assetName", string(c.get("logoName"), ""),
                    "alt", string(c.get("logoName"), "Логотип"), "href", "", "radius", "NONE",
                    "alignment", "LEFT", "width", 150, "height", 64, "keepAspectRatio", true,
                    "aspectRatio", null, "viewportHeight", null)));
        }
        if (company != null) {
            int span = parts == 1 ? 12 : parts == 2 ? (hasLogo && phone == null ? 8 : 8) : 6;
            out.add(new PageBlockView(base + "-company", "TEXT", visibility, rowId, span, mapOf(
                    "mode", "STATIC", "style", "HEADING", "text", company, "label", "Название компании",
                    "placeholder", "", "required", false, "alignment", "CENTER", "fontFamily", "DEFAULT",
                    "fontSize", 24, "bold", true, "italic", false, "underline", false)));
        }
        if (phone != null) {
            int used = out.stream().mapToInt(block -> block.span() == null ? 0 : block.span()).sum();
            int span = parts == 1 ? 12 : Math.max(3, 12 - used);
            out.add(new PageBlockView(base + "-phone", "ICON_TEXT", visibility, rowId, span, mapOf(
                    "icon", "PHONE", "text", phone, "href", string(c.get("phoneHref"), ""),
                    "iconColor", "#2f80ed", "iconSize", 22, "textColor", "#344f5f", "alignment", "RIGHT",
                    "fontFamily", "DEFAULT", "fontSize", 14, "bold", true, "italic", false, "underline", false)));
        }
        return out;
    }

    private String normalizeRowId(String value, String blockId, int sequence) {
        String candidate = trimToNull(value);
        if (candidate != null && SAFE_ID.matcher(candidate).matches()) return candidate;
        return generatedRowId(blockId, sequence);
    }

    private String generatedRowId(String blockId, int sequence) {
        String hash = Integer.toUnsignedString(Objects.hash(blockId, sequence), 36);
        return "row-" + hash + "-" + Math.abs(sequence % 100000);
    }

    private Map<String, Object> normalizeConfig(String type, Map<String, Object> raw, Long tenantId, String companyName) {
        Map<String, Object> c = raw == null ? Map.of() : raw;
        Map<String, Object> out = new LinkedHashMap<>();
        switch (type) {
            case "VIDEO" -> {
                String source = string(c.get("source"), "MAIN").toUpperCase(Locale.ROOT);
                if (!Set.of("MAIN", "STATIC").contains(source)) source = "MAIN";
                out.put("source", source);
                out.put("title", limit(string(c.get("title"), ""), 255));
                out.put("assetUrl", "STATIC".equals(source) ? assetUrl(c.get("assetUrl"), tenantId) : null);
                out.put("assetName", limit(string(c.get("assetName"), ""), 255));
            }
            case "TEXT" -> {
                String mode = string(c.get("mode"), "STATIC").toUpperCase(Locale.ROOT);
                if (!Set.of("STATIC", "MANAGER").contains(mode)) mode = "STATIC";
                String style = string(c.get("style"), "PARAGRAPH").toUpperCase(Locale.ROOT);
                if (!Set.of("HEADING", "PARAGRAPH", "NOTE").contains(style)) style = "PARAGRAPH";
                out.put("mode", mode);
                out.put("style", style);
                out.put("text", limit(string(c.get("text"), ""), 20_000));
                out.put("label", limit(string(c.get("label"), "Текст"), 255));
                out.put("placeholder", limit(string(c.get("placeholder"), ""), 500));
                out.put("required", bool(c.get("required"), false));
                putTypography(out, c, "HEADING".equals(style), "LEFT");
                String fieldKey = string(c.get("fieldKey"), null);
                if (fieldKey != null) out.put("fieldKey", limit(fieldKey, 100));
            }
            case "IMAGE" -> {
                out.put("assetUrl", assetUrl(c.get("assetUrl"), tenantId));
                out.put("assetName", limit(string(c.get("assetName"), ""), 255));
                out.put("alt", limit(string(c.get("alt"), ""), 500));
                out.put("href", safeHref(string(c.get("href"), "")));
                String radius = string(c.get("radius"), "LARGE").toUpperCase(Locale.ROOT);
                out.put("radius", Set.of("NONE", "SMALL", "LARGE").contains(radius) ? radius : "LARGE");
                out.put("alignment", alignment(c.get("alignment"), "CENTER"));
                out.put("width", boundedInteger(c.get("width"), 1, 5000));
                out.put("height", boundedInteger(c.get("height"), 1, 5000));
                out.put("keepAspectRatio", bool(c.get("keepAspectRatio"), true));
                out.put("aspectRatio", boundedDouble(c.get("aspectRatio"), 0.02d, 50d));
                out.put("viewportHeight", boundedInteger(c.get("viewportHeight"), 40, 3000));
            }
            case "FILE" -> {
                String mode = string(c.get("mode"), "STATIC").toUpperCase(Locale.ROOT);
                if (!Set.of("STATIC", "MANAGER").contains(mode)) mode = "STATIC";
                out.put("mode", mode);
                out.put("assetUrl", "STATIC".equals(mode) ? assetUrl(c.get("assetUrl"), tenantId) : null);
                out.put("assetName", limit(string(c.get("assetName"), ""), 255));
                out.put("label", limit(string(c.get("label"), "Скачать файл"), 255));
                out.put("required", "MANAGER".equals(mode) && bool(c.get("required"), false));
                out.put("alignment", alignment(c.get("alignment"), "LEFT"));
            }
            case "BUTTON" -> {
                out.put("text", limit(string(c.get("text"), "Подробнее"), 500));
                out.put("href", safeHref(string(c.get("href"), "")));
                out.put("color", hexColor(c.get("color"), "#2f80ed"));
                String shape = string(c.get("shape"), "PILL").toUpperCase(Locale.ROOT);
                out.put("shape", Set.of("SQUARE", "ROUNDED", "PILL").contains(shape) ? shape : "PILL");
                String depth = string(c.get("depth"), "FLAT").toUpperCase(Locale.ROOT);
                out.put("depth", Set.of("FLAT", "SUBTLE", "RAISED", "DEEP").contains(depth) ? depth : "FLAT");
                String shadow = string(c.get("shadow"), "NONE").toUpperCase(Locale.ROOT);
                out.put("shadow", Set.of("NONE", "SOFT", "MEDIUM", "STRONG").contains(shadow) ? shadow : "NONE");
                String hover = string(c.get("hoverAnimation"), "LIFT").toUpperCase(Locale.ROOT);
                out.put("hoverAnimation", Set.of("NONE", "LIFT", "GROW", "GLOW", "BRIGHTEN").contains(hover) ? hover : "LIFT");
                String click = string(c.get("clickAnimation"), "PRESS").toUpperCase(Locale.ROOT);
                out.put("clickAnimation", Set.of("NONE", "PRESS", "SHRINK", "BOUNCE").contains(click) ? click : "PRESS");
                out.put("newTab", bool(c.get("newTab"), false));
                putTypography(out, c, true, "CENTER");
            }
            case "ICON_TEXT" -> {
                String icon = string(c.get("icon"), "PHONE").toUpperCase(Locale.ROOT);
                if (!Set.of("PHONE", "MAIL", "LOCATION", "LINK", "MESSAGE", "CLOCK", "USER", "CHECK", "INFO").contains(icon)) icon = "PHONE";
                out.put("icon", icon);
                out.put("text", limit(string(c.get("text"), "Телефон или подпись"), 1000));
                out.put("href", safeHref(string(c.get("href"), "")));
                out.put("iconColor", hexColor(c.get("iconColor"), "#2f80ed"));
                out.put("iconSize", Optional.ofNullable(boundedInteger(c.get("iconSize"), 12, 96)).orElse(24));
                out.put("textColor", hexColor(c.get("textColor"), "#344f5f"));
                putTypography(out, c, false, "LEFT");
            }
            case "DIVIDER" -> {
                String style = string(c.get("style"), "SOLID").toUpperCase(Locale.ROOT);
                out.put("style", Set.of("SOLID", "DASHED", "DOTTED").contains(style) ? style : "SOLID");
            }
            case "EMBED" -> {
                String codeType = string(c.get("codeType"), "HTML").toUpperCase(Locale.ROOT);
                out.put("codeType", Set.of("HTML", "JAVASCRIPT").contains(codeType) ? codeType : "HTML");
                out.put("code", limit(string(c.get("code"), ""), 100_000));
            }
        }
        return out;
    }

    private void putTypography(Map<String, Object> out, Map<String, Object> c, boolean boldDefault, String alignmentDefault) {
        out.put("alignment", alignment(c.get("alignment"), alignmentDefault));
        out.put("fontFamily", fontFamily(c.get("fontFamily")));
        out.put("fontSize", boundedInteger(c.get("fontSize"), 8, 120));
        out.put("bold", bool(c.get("bold"), boldDefault));
        out.put("italic", bool(c.get("italic"), false));
        out.put("underline", bool(c.get("underline"), false));
    }

    private String hexColor(Object value, String fallback) {
        String color = string(value, fallback);
        return HEX_COLOR.matcher(color).matches() ? color : fallback;
    }

    private PageTemplateView defaultTemplate(String companyName) {
        List<PageBlockView> blocks = List.of(
                new PageBlockView("company-default", "TEXT", "ALL", "row-company-default", 12, mapOf(
                        "mode", "STATIC", "style", "HEADING", "text", companyName == null ? "Video Offer" : companyName,
                        "label", "Название компании", "placeholder", "", "required", false,
                        "alignment", "CENTER", "fontFamily", "DEFAULT", "fontSize", 28,
                        "bold", true, "italic", false, "underline", false)),
                new PageBlockView("title-default", "TEXT", "ALL", "row-title-default", 12, mapOf(
                        "mode", "STATIC", "style", "HEADING", "text", "Материалы по итогам разговора",
                        "label", "Заголовок", "placeholder", "", "required", false,
                        "alignment", "LEFT", "fontFamily", "DEFAULT", "fontSize", null,
                        "bold", true, "italic", false, "underline", false)),
                new PageBlockView("main-video-default", "VIDEO", "ALL", "row-main-video-default", 12, mapOf(
                        "source", "MAIN", "title", "", "assetUrl", null, "assetName", "")),
                new PageBlockView("accompanying-default", "TEXT", "ALL", "row-accompanying-default", 12, mapOf(
                        "mode", "MANAGER", "style", "NOTE", "text", "", "label", "Сопроводительный текст",
                        "placeholder", "Например: направляю короткую презентацию по итогам разговора.",
                        "required", false, "fieldKey", "accompanyingText", "alignment", "LEFT",
                        "fontFamily", "DEFAULT", "fontSize", null, "bold", false, "italic", false, "underline", false))
        );
        return new PageTemplateView(TEMPLATE_VERSION, blocks);
    }

    private String assetUrl(Object value, Long tenantId) {
        String url = trimToNull(string(value, null));
        if (url == null) return null;
        if (tenantId != null && !url.startsWith("/page-assets/" + tenantId + "/")) throw new IllegalArgumentException("Файл шаблона относится к другой компании");
        if (!url.matches("/page-assets/\\d+/[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Некорректная ссылка на файл шаблона");
        return url;
    }

    private String alignment(Object value, String fallback) {
        String normalized = string(value, fallback).toUpperCase(Locale.ROOT);
        return ALIGNMENT.contains(normalized) ? normalized : fallback;
    }

    private String fontFamily(Object value) {
        String normalized = string(value, "DEFAULT").toUpperCase(Locale.ROOT);
        return FONT_FAMILIES.contains(normalized) ? normalized : "DEFAULT";
    }

    private Integer boundedInteger(Object value, int min, int max) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            int number = (int) Math.round(Double.parseDouble(String.valueOf(value)));
            return number >= min && number <= max ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double boundedDouble(Object value, double min, double max) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            double number = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(number) && number >= min && number <= max ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String safeHref(String value) {
        String href = trimToNull(value);
        if (href == null) return "";
        href = limit(href, 2000);
        try {
            URI uri = URI.create(href);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException();
                return href;
            }
            if ("tel".equals(scheme) || "mailto".equals(scheme)) {
                String specific = uri.getSchemeSpecificPart();
                if (specific == null || specific.isBlank()) throw new IllegalArgumentException();
                return href;
            }
        } catch (RuntimeException ignored) {
            // Unified validation message below.
        }
        throw new IllegalArgumentException("Ссылка должна быть корректной и начинаться с https://, http://, tel: или mailto:");
    }

    private void validateFile(MultipartFile file, Set<String> extensions, long maxBytes, String label) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Выберите " + label);
        if (file.getSize() <= 0 || file.getSize() > maxBytes) throw new IllegalArgumentException("Размер файла превышает допустимый лимит");
        String ext = extension(file.getOriginalFilename());
        if (!extensions.contains(ext)) throw new IllegalArgumentException("Формат файла не поддерживается: ." + ext);
    }

    private String companyName(Long tenantId) {
        if (tenantId == null) return "Video Offer";
        return tenantRepository.findById(tenantId).map(VideoOfferTenant::getName).orElse("Video Offer");
    }

    private VideoOfferTenant requiredTenant(long tenantId) {
        return tenantRepository.findById(tenantId).orElseThrow(() -> new NoSuchElementException("Компания Video Offer не найдена"));
    }

    private Path draftDirectory(long tenantId, long userId) { return root.resolve("drafts").resolve(Long.toString(tenantId)).resolve(Long.toString(userId)); }
    private Path attachmentDirectory(Long tenantId, UUID offerId) { return root.resolve("attachments").resolve(String.valueOf(tenantId == null ? 0L : tenantId)).resolve(offerId.toString()); }

    private void deleteTreeQuietly(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> { try { Files.deleteIfExists(item); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private void requireInside(Path path, Path parent) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedParent)) throw new IllegalArgumentException("Некорректный путь файла");
    }

    private String extension(String name) {
        String safe = name == null ? "" : name.trim();
        int dot = safe.lastIndexOf('.');
        if (dot < 0 || dot == safe.length() - 1) return "";
        return safe.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String safeOriginalName(String name) {
        String safe = name == null ? "file" : name.replace('\\', '/');
        int slash = safe.lastIndexOf('/');
        if (slash >= 0) safe = safe.substring(slash + 1);
        safe = safe.replaceAll("[\\r\\n\\t]", " ").trim();
        if (safe.isEmpty()) safe = "file";
        return limit(safe, 255);
    }

    private String probe(Path path, String fallback) {
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && !probed.isBlank()) return probed;
        } catch (IOException ignored) {}
        return fallback == null || fallback.isBlank() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : fallback;
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Не удалось сохранить настройки страницы", error); }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }

    private String string(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value)) || "Y".equalsIgnoreCase(String.valueOf(value));
    }
    private String trimToNull(String value) { if (value == null) return null; String v = value.trim(); return v.isEmpty() ? null : v; }
    private String limit(String value, int max) { if (value == null) return null; return value.length() <= max ? value : value.substring(0, max); }

    private enum AssetKind {
        IMAGE("изображение", IMAGE_EXT, IMAGE_MAX), VIDEO("видео", VIDEO_EXT, VIDEO_MAX), FILE("файл", FILE_EXT, FILE_MAX);
        final String label; final Set<String> allowedExtensions; final long maxBytes;
        AssetKind(String label, Set<String> allowedExtensions, long maxBytes) { this.label = label; this.allowedExtensions = allowedExtensions; this.maxBytes = maxBytes; }
        static AssetKind from(String value) {
            try { return AssetKind.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT)); }
            catch (Exception error) { throw new IllegalArgumentException("Неизвестный тип файла"); }
        }
    }

    public record PageTemplateView(int version, List<PageBlockView> blocks) {}
    public record PageBlockView(String id, String type, String visibility, String rowId, Integer span, Map<String, Object> config) {
        public PageBlockView(String id, String type, String visibility, Map<String, Object> config) {
            this(id, type, visibility, null, null, config);
        }
    }
    public record OfferPagePrepared(String templateJson, String contentJson) {}
    public record OfferPageContent(Map<String, String> text, Map<String, OfferAttachmentView> files) {
        public OfferPageContent {
            text = text == null ? Map.of() : Map.copyOf(text);
            files = files == null ? Map.of() : Map.copyOf(files);
        }
        static OfferPageContent empty() { return new OfferPageContent(Map.of(), Map.of()); }
    }
    public record OfferAttachmentView(String id, String fileName, String contentType, long size, String storedName) {}
    public record AssetUploadResponse(String id, String url, String fileName, long size, String contentType, String kind) {}
    public record DraftUploadResponse(String draftId, String fileName, String contentType, long size) {}
    public record AssetFile(Path path, String contentType, String fileName) {}
    private record DraftMeta(String id, String blockId, String fileName, String contentType, long size, String createdAt) {}
}
