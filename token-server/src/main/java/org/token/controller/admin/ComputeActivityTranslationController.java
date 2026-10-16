package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 活动翻译仅修改独立表单草稿；读取按真实活动分类和原管理员复核。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/activity-translations")
@RequiredArgsConstructor
public class ComputeActivityTranslationController {
    private final org.token.service.compute.ComputeActivityFormService service;
    private final ResultFactory resultFactory;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('50201','50301','50401','super')")
    @LogAction(type="活动表单翻译")
    public Result submit(@RequestBody Map<String,Object> request,@AuthenticationPrincipal JwtUserPrincipal admin) {
        var outcome = service.submit(request, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/{taskId}")
    @PreAuthorize("hasAnyAuthority('50200','50201','50300','50301','50400','50401','super')")
    public Result progress(@PathVariable long taskId,@AuthenticationPrincipal JwtUserPrincipal admin) {
        var outcome = service.progress(taskId, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
