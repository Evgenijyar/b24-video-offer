package ru.abs7.videooffer.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
public class BackofficeController {
    private final BackofficeAuthService authService;
    private final TenantAdminService adminService;
    private final TenantOfferService offerService;
    private final PageTemplateService pageTemplateService;
    private final String backofficeTemplate;
    private final String loginTemplate;

    public BackofficeController(BackofficeAuthService authService, TenantAdminService adminService, TenantOfferService offerService, PageTemplateService pageTemplateService) throws IOException {
        this.authService = authService;
        this.adminService = adminService;
        this.offerService = offerService;
        this.pageTemplateService = pageTemplateService;
        this.backofficeTemplate = new ClassPathResource("backoffice/backoffice.html").getContentAsString(StandardCharsets.UTF_8);
        this.loginTemplate = new ClassPathResource("backoffice/backoffice-login.html").getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/backoffice", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> backoffice(HttpSession session) {
        String html = authService.isAuthenticated(session)
                ? backofficeTemplate.replace("{{BACKOFFICE_CSRF}}", authService.csrfToken(session))
                : loginTemplate;
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .body(html);
    }

    @PostMapping(value = "/api/backoffice/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest, HttpSession session) {
        if (!authService.login(request.username(), request.password(), servletRequest.getRemoteAddr(), session)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "message", "Неверный логин или пароль"));
        }
        // Rotate the container session id after successful authentication to prevent session fixation.
        servletRequest.changeSessionId();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping(value = "/api/backoffice/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> logout(
            HttpSession session,
            @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) {
        authService.requireMutation(session, csrfToken);
        authService.logout(session);
        return Map.of("ok", true);
    }

    @GetMapping(value = "/api/backoffice/tenants", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TenantAdminService.TenantSummary> tenants(HttpSession session) {
        authService.require(session);
        return adminService.list();
    }

    @GetMapping(value = "/api/backoffice/tenants/{tenantId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TenantAdminService.TenantDetails tenant(@PathVariable long tenantId, HttpSession session) {
        authService.require(session);
        return adminService.details(tenantId);
    }

    @PostMapping(value = "/api/backoffice/tenants", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TenantAdminService.TenantDetails create(
            @RequestBody TenantAdminService.CreateTenantRequest request,
            HttpSession session,
            @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) {
        authService.requireMutation(session, csrfToken);
        return adminService.create(request);
    }

    @PutMapping(value = "/api/backoffice/tenants/{tenantId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TenantAdminService.TenantDetails update(@PathVariable long tenantId,
                                                   @RequestBody TenantAdminService.UpdateTenantRequest request,
                                                   HttpSession session,
                                                   @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) {
        authService.requireMutation(session, csrfToken);
        return adminService.update(tenantId, request);
    }

    @GetMapping(value = "/api/backoffice/tenants/{tenantId}/offers", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TenantOfferService.OfferView> offers(@PathVariable long tenantId, HttpSession session) {
        authService.require(session);
        return offerService.activeOffers(tenantId);
    }

    @GetMapping(value = "/api/backoffice/tenants/{tenantId}/page-template", produces = MediaType.APPLICATION_JSON_VALUE)
    public PageTemplateService.PageTemplateView pageTemplate(@PathVariable long tenantId, HttpSession session) {
        authService.require(session);
        return pageTemplateService.template(tenantId);
    }

    @PutMapping(value = "/api/backoffice/tenants/{tenantId}/page-template", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PageTemplateService.PageTemplateView savePageTemplate(
            @PathVariable long tenantId,
            @RequestBody PageTemplateService.PageTemplateView request,
            HttpSession session,
            @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) {
        authService.requireMutation(session, csrfToken);
        return pageTemplateService.saveTemplate(tenantId, request);
    }

    @PostMapping(value = "/api/backoffice/tenants/{tenantId}/page-template/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PageTemplateService.AssetUploadResponse uploadPageAsset(
            @PathVariable long tenantId,
            @RequestParam String kind,
            @RequestPart("file") MultipartFile file,
            HttpSession session,
            @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) throws IOException {
        authService.requireMutation(session, csrfToken);
        return pageTemplateService.uploadTemplateAsset(tenantId, kind, file);
    }

    @PostMapping(value = "/api/backoffice/tenants/{tenantId}/test", produces = MediaType.APPLICATION_JSON_VALUE)
    public TenantAdminService.ConnectionTest test(
            @PathVariable long tenantId, HttpSession session,
            @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) {
        authService.requireMutation(session, csrfToken);
        return adminService.testConnection(tenantId);
    }

    @PostMapping(value = "/api/backoffice/tenants/{tenantId}/sync-users", produces = MediaType.APPLICATION_JSON_VALUE)
    public TenantAdminService.TenantDetails syncUsers(
            @PathVariable long tenantId, HttpSession session,
            @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) {
        authService.requireMutation(session, csrfToken);
        return adminService.syncUsers(tenantId);
    }

    @PutMapping(value = "/api/backoffice/tenants/{tenantId}/primary-admin/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TenantAdminService.TenantDetails primaryAdmin(
            @PathVariable long tenantId, @PathVariable long userId, HttpSession session,
            @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) {
        authService.requireMutation(session, csrfToken);
        return adminService.setPrimaryAdmin(tenantId, userId);
    }

    @PutMapping(value = "/api/backoffice/tenants/{tenantId}/users", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public TenantAdminService.TenantDetails updateUsers(@PathVariable long tenantId,
                                                        @RequestBody UserBatchRequest request,
                                                        HttpSession session,
                                                        @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) {
        authService.requireMutation(session, csrfToken);
        return adminService.updateUsers(tenantId, request.users(), false);
    }

    @PostMapping(value = "/api/backoffice/tenants/{tenantId}/reset-usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public TenantAdminService.TenantDetails resetUsage(
            @PathVariable long tenantId, HttpSession session,
            @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) {
        authService.requireMutation(session, csrfToken);
        return adminService.resetUsage(tenantId);
    }

    @DeleteMapping("/api/backoffice/tenants/{tenantId}")
    public ResponseEntity<Void> delete(
            @PathVariable long tenantId, HttpSession session,
            @RequestHeader(value = "X-Backoffice-CSRF", required = false) String csrfToken) {
        authService.requireMutation(session, csrfToken);
        adminService.delete(tenantId);
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(String username, String password) {}
    public record UserBatchRequest(List<TenantAdminService.UserConfigRequest> users) {}
}
