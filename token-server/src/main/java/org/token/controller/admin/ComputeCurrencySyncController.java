package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeMcagConfigSyncService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 复用原币网数字权限；服务内继续校验全局数据范围。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/currency-sync")
@RequiredArgsConstructor
public class ComputeCurrencySyncController {
    private final ComputeMcagConfigSyncService service;
    private final ResultFactory results;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('70400','70401','70402','super')")
    public Result status() {return results.success(service.status());}

    @PostMapping
    @PreAuthorize("hasAnyAuthority('70401','70402','super')")
    @LogAction(type="同步Crypto币网配置")
    public Result synchronize() {return results.success(service.synchronize());}
}
