package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputePresentationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 沿用平台配置权限与用户独立认证，只读估值不会改动资金。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputePresentationController {
    private final ComputePresentationService service;
    private final ComputeAgentScope scope;
    private final ResultFactory results;

    @GetMapping("/admin/presentation-settings")
    @PreAuthorize("hasAnyAuthority('61200','61201','super')")
    public Result settings() { scope.requireGlobalScope(); return results.success(service.adminSettings()); }

    @PutMapping("/admin/presentation-settings")
    @PreAuthorize("hasAnyAuthority('61201','super')")
    @LogAction(type = "用户端展示配置")
    public Result save(@RequestBody Map<String, Object> request) { scope.requireGlobalScope(); return results.success(service.save(request)); }

}
