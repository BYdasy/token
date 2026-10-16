package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 复用提款费率权限，唯一全局次数配置独立于具体费率与免费次数。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/fee-rules/withdrawal-limit")
@RequiredArgsConstructor
public class ComputeWithdrawalLimitController {
    private final org.token.service.compute.ComputeWithdrawalSettingsService service;
    private final ResultFactory results;
    @GetMapping
    @PreAuthorize("hasAnyAuthority('61400','61401','super')")
    public Result read() { return results.success(service.limit()); }
    @PutMapping
    @LogAction(type="用户提款次数配置")
    @PreAuthorize("hasAnyAuthority('61401','super')")
    public Result save(@RequestBody Map<String,Object> body) { return results.success(service.saveLimit(body)); }
}
