package ru.abs7.videooffer.tenant;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.abs7.videooffer.bitrix.BitrixContextSigner;
import ru.abs7.videooffer.bitrix.BitrixPlacementContext;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/bitrix/settings")
public class TenantClientSettingsController {
    private final BitrixContextSigner contextSigner;
    private final TenantAccessService accessService;
    private final TenantAdminService adminService;
    private final TenantOfferService offerService;
    private final PageTemplateService pageTemplateService;

    public TenantClientSettingsController(
            BitrixContextSigner contextSigner,
            TenantAccessService accessService,
            TenantAdminService adminService,
            TenantOfferService offerService,
            PageTemplateService pageTemplateService) {
        this.contextSigner = contextSigner;
        this.accessService = accessService;
        this.adminService = adminService;
        this.offerService = offerService;
        this.pageTemplateService = pageTemplateService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ClientSettingsView settings(@RequestParam String contextToken) {
        BitrixPlacementContext context = verifiedAdminContext(contextToken);
        return ClientSettingsView.from(adminService.details(context.tenantId()), context.bitrixUserId());
    }

    @GetMapping(value = "/offers", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TenantOfferService.OfferView> offers(@RequestParam String contextToken) {
        BitrixPlacementContext context = verifiedAdminContext(contextToken);
        return offerService.activeOffers(context.tenantId());
    }

    @GetMapping(value = "/page-template", produces = MediaType.APPLICATION_JSON_VALUE)
    public PageTemplateService.PageTemplateView pageTemplate(@RequestParam String contextToken) {
        BitrixPlacementContext context = verifiedAdminContext(contextToken);
        return pageTemplateService.template(context.tenantId());
    }

    @PutMapping(value = "/page-template", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PageTemplateService.PageTemplateView savePageTemplate(
            @RequestParam String contextToken,
            @RequestBody PageTemplateService.PageTemplateView request) {
        BitrixPlacementContext context = verifiedAdminContext(contextToken);
        return pageTemplateService.saveTemplate(context.tenantId(), request);
    }

    @PostMapping(value = "/page-template/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PageTemplateService.AssetUploadResponse uploadPageAsset(
            @RequestParam String contextToken,
            @RequestParam String kind,
            @RequestPart("file") MultipartFile file) throws IOException {
        BitrixPlacementContext context = verifiedAdminContext(contextToken);
        return pageTemplateService.uploadTemplateAsset(context.tenantId(), kind, file);
    }

    @PostMapping(value = "/sync-users", produces = MediaType.APPLICATION_JSON_VALUE)
    public ClientSettingsView syncUsers(@RequestParam String contextToken) {
        BitrixPlacementContext context = verifiedAdminContext(contextToken);
        return ClientSettingsView.from(adminService.syncUsers(context.tenantId()), context.bitrixUserId());
    }

    @PutMapping(value = "/users", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ClientSettingsView updateUsers(
            @RequestParam String contextToken,
            @RequestBody UserBatchRequest request) {
        BitrixPlacementContext context = verifiedAdminContext(contextToken);
        TenantAdminService.TenantDetails details = adminService.updateUsers(context.tenantId(), request.users(), true);
        return ClientSettingsView.from(details, context.bitrixUserId());
    }

    private BitrixPlacementContext verifiedAdminContext(String contextToken) {
        BitrixPlacementContext context = contextSigner.verify(contextToken);
        VideoOfferTenant tenant = accessService.requiredTenant(context.tenantId(), context.memberId());
        VideoOfferTenantUser user = accessService.requiredUser(tenant.getId(), context.bitrixUserId());
        if (!tenant.isActive() || !user.isActive() || !user.hasOfferAccess() || !user.isAdmin()) {
            throw new IllegalArgumentException("Настройки доступны только администратору Video Offer");
        }
        return context;
    }

    public record UserBatchRequest(List<TenantAdminService.UserConfigRequest> users) {}

    public record ClientSettingsView(
            Long tenantId,
            String companyName,
            String packageName,
            int seatLimit,
            long seatsUsed,
            int offerLimit,
            long offersUsed,
            long offersRemaining,
            long diskQuotaBytes,
            long diskUsedBytes,
            long diskRemainingBytes,
            int retentionDays,
            boolean allowAnyEntity,
            Long primaryAdminUserId,
            Long currentUserId,
            String pageSettingsStatus,
            List<TenantAdminService.TenantUserView> users) {
        static ClientSettingsView from(TenantAdminService.TenantDetails details, Long currentUserId) {
            return new ClientSettingsView(
                    details.id(), details.name(), details.packageName(),
                    details.seatLimit(), details.seatsUsed(),
                    details.offerLimit(), details.offersUsed(), Math.max(0, details.offerLimit() - details.offersUsed()),
                    details.diskQuotaBytes(), details.diskUsedBytes(), Math.max(0, details.diskQuotaBytes() - details.diskUsedBytes()),
                    details.retentionDays(), details.allowAnyEntity(), details.primaryAdminUserId(), currentUserId,
                    "Конструктор страницы доступен во вкладке «Страница»",
                    details.users());
        }
    }
}
