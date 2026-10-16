package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.custom.LoggingAspect;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeExchangeProviderSettings;
import org.token.service.compute.ComputeExchangeRateService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 与基础业务配置共用数字权限和全局数据范围。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/exchange-settings")
@RequiredArgsConstructor
public class ComputeExchangeSettingsController {
    private final ComputeExchangeProviderSettings settings;
    private final ComputeExchangeRateService rates;
    private final ComputeAgentScope scope;
    private final ResultFactory results;
    @GetMapping
    @PreAuthorize("hasAnyAuthority('61100','61101','super')")
    public Result settings() { scope.requireGlobalScope(); return results.success(settings.publicSettings()); }
    @GetMapping("/status")
    @PreAuthorize("hasAnyAuthority('61100','61101','super')")
    public Result status() { scope.requireGlobalScope(); return results.success(rates.status()); }
    @PutMapping("/{provider}")
    @PreAuthorize("hasAnyAuthority('61101','super')")
    @LogAction(type="汇率接口配置")
    public Result save(@PathVariable String provider, @RequestBody Map<String,Object> request) {
        scope.requireGlobalScope();
        Map<String,Object> value = settings.save(provider, request);
        LoggingAspect.setLogDetails("更新汇率接口配置：" + provider, 1);
        return results.success(value);
    }
    @PostMapping("/{provider}/refresh")
    @PreAuthorize("hasAnyAuthority('61101','super')")
    @LogAction(type="汇率接口更新")
    public Result refresh(@PathVariable String provider) {
        scope.requireGlobalScope();
        return results.success(rates.requestRefresh(provider));
    }
}
