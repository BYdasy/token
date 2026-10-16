package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 当前表单的异步翻译预览，结果不修改业务内容，查询严格绑定提交账号。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/translation-forms")
@RequiredArgsConstructor
public class ComputeFormTranslationController {
    private final org.token.service.compute.ComputeFormTranslationService service;
    private final ResultFactory resultFactory;

    @PostMapping("/CONTENT")
    @org.token.custom.LogAction(type = "内容表单翻译")
    @PreAuthorize("(hasAnyAuthority('super')) or ((#body['kind'] == 'MESSAGE' and hasAnyAuthority('70501')) or (#body['kind'] == 'TASK' and hasAnyAuthority('70601')) or (#body['kind'] == 'ANNOUNCEMENT' and hasAnyAuthority('70801')) or (#body['kind'] == 'ARTICLE' and hasAnyAuthority('70901')))")
    public Result content(@RequestBody Map<String,Object> body,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.content(body, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/CONTENT/{taskId}")
    @PreAuthorize("hasAnyAuthority('70501','70601','70801','70901','super')")
    public Result contentTask(@PathVariable long taskId,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.contentTask(taskId, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/{resource:PRODUCT|LEVEL}")
    @org.token.custom.LogAction(type = "产品等级表单翻译")
    @PreAuthorize("(hasAnyAuthority('super')) or ((#resource == 'PRODUCT' and hasAnyAuthority('70101')) or (#resource == 'LEVEL' and hasAnyAuthority('70201')))")
    public Result catalog(@PathVariable String resource,@RequestBody Map<String,Object> body,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.catalog(resource, body, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/{resource:PRODUCT|LEVEL}/{taskId}")
    @PreAuthorize("(hasAnyAuthority('super')) or ((#resource == 'PRODUCT' and hasAnyAuthority('70101')) or (#resource == 'LEVEL' and hasAnyAuthority('70201')))")
    public Result catalogTask(@PathVariable String resource,@PathVariable long taskId,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.catalogTask(resource, taskId, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/COMPANY_FILE")
    @org.token.custom.LogAction(type = "公司文件标题翻译")
    @PreAuthorize("hasAnyAuthority('super','71001')")
    public Result companyFile(@RequestBody Map<String,Object> body,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.companyFile(body, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/COMPANY_FILE/{taskId}")
    @PreAuthorize("hasAnyAuthority('super','71001')")
    public Result companyFileTask(@PathVariable long taskId,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.companyFileTask(taskId, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
