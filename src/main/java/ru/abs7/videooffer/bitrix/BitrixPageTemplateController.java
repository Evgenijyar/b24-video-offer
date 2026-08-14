package ru.abs7.videooffer.bitrix;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.abs7.videooffer.tenant.PageTemplateService;
import ru.abs7.videooffer.tenant.TenantAccessService;

import java.io.IOException;

@RestController
@RequestMapping("/bitrix/page")
public class BitrixPageTemplateController {
    private final BitrixContextSigner contextSigner;
    private final TenantAccessService accessService;
    private final PageTemplateService pageTemplateService;

    public BitrixPageTemplateController(
            BitrixContextSigner contextSigner,
            TenantAccessService accessService,
            PageTemplateService pageTemplateService) {
        this.contextSigner = contextSigner;
        this.accessService = accessService;
        this.pageTemplateService = pageTemplateService;
    }

    @GetMapping(value = "/template", produces = MediaType.APPLICATION_JSON_VALUE)
    public PageTemplateService.PageTemplateView template(@RequestParam String contextToken) {
        BitrixPlacementContext context = contextSigner.verify(contextToken);
        accessService.assertContextCanCreate(context);
        return pageTemplateService.template(context.tenantId());
    }

    @PostMapping(value = "/draft-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public PageTemplateService.DraftUploadResponse uploadDraftFile(
            @RequestParam String contextToken,
            @RequestParam String blockId,
            @RequestPart("file") MultipartFile file) throws IOException {
        BitrixPlacementContext context = contextSigner.verify(contextToken);
        accessService.assertContextCanCreate(context);
        return pageTemplateService.uploadDraftFile(context.tenantId(), context.bitrixUserId(), blockId, file);
    }
}
