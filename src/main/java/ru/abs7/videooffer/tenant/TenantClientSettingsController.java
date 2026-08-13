package ru.abs7.videooffer.tenant;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import ru.abs7.videooffer.bitrix.BitrixContextSigner;
import ru.abs7.videooffer.bitrix.BitrixPlacementContext;

import java.util.List;

@RestController
@RequestMapping("/bitrix/settings")
public class TenantClientSettingsController {
    private final BitrixContextSigner contextSigner;
    private final TenantAccessService accessService;
    private final TenantAdminService adminService;

    public TenantClientSettingsController(
            BitrixContextSigner contextSigner,
            TenantAccessService accessService,
            TenantAdminService adminService) {
        this.contextSigner = contextSigner;
        this.accessService = accessService;
        this.adminService = adminService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ClientSettingsView settings(@RequestParam String contextToken) {
        BitrixPlacementContext context = verifiedAdminContext(contextToken);
        return ClientSettingsView.from(adminService.details(context.tenantId()), context.bitrixUserId());
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
                    details.allowAnyEntity(), details.primaryAdminUserId(), currentUserId,
                    "Раздел шаблона клиентской страницы зарезервирован для следующего этапа",
                    details.users());
        }
    }
}
