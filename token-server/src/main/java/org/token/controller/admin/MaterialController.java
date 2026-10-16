package org.token.controller.admin;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.request.system.MaterialUpdateRequest;
import org.token.model.security.JwtUserPrincipal;

import java.util.Map;

@RestController
@RequestMapping("/api/V2/materials")
@RequiredArgsConstructor
public class MaterialController {
    private static final String SELECT_PERMISSION =
            "hasAnyAuthority('60102','60201','60202','60500','6060202','6060203','6060302','6060303','6060402','6060403','super')";
    private static final String UPLOAD_PERMISSION =
            "hasAnyAuthority('60102','60201','60202','60501','6060202','6060203','6060302','6060303','6060402','6060403','super')";
    private static final String UPDATE_PERMISSION = "hasAnyAuthority('60502','super')";
    private static final String DELETE_PERMISSION = "hasAnyAuthority('60503','super')";

    private final org.token.service.system.MaterialAssetService service;
    private final ResultFactory resultFactory;

    @PostMapping("/page")
    @PreAuthorize(SELECT_PERMISSION)
    public Result page(
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        var outcome = service.page(body, principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/categories")
    @PreAuthorize(SELECT_PERMISSION)
    public Result categories(@AuthenticationPrincipal JwtUserPrincipal principal) {
        var outcome = service.categories(principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/upload")
    @PreAuthorize(UPLOAD_PERMISSION)
    @LogAction(type = "素材上传")
    public Result upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String remark,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) throws Exception {
        var outcome = service.upload(file, name, category, tags, remark, principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/{id}/update")
    @PreAuthorize(UPDATE_PERMISSION)
    @LogAction(type = "素材修改")
    public Result update(@PathVariable Long id, @RequestBody MaterialUpdateRequest request) {
        var outcome = service.update(id, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(DELETE_PERMISSION)
    @LogAction(type = "素材删除")
    public Result delete(@PathVariable Long id) {
        var outcome = service.delete(id);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
