package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeLiveMonitorService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@org.token.custom.web.ComputeApi
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/compute/admin")
public class ComputeLiveMonitorController {
    private final ComputeLiveMonitorService monitor;
    private final ResultFactory results;

    @GetMapping("/live-status")
    @PreAuthorize("hasAnyAuthority('20100','20101','20102','20103','20104','20105','20106','30200','30201','super')")
    public Result status() { return results.success(monitor.status()); }

    @GetMapping("/monitor-settings")
    @PreAuthorize("hasAnyAuthority('60101','60102','super')")
    public Result settings() { return results.success(monitor.settings()); }

    @PutMapping("/monitor-settings")
    @PreAuthorize("hasAnyAuthority('60102','super')")
    @LogAction(type="在线监控时间窗口设置")
    public Result save(@RequestBody Map<String,Object> input) { return results.success(monitor.save(input)); }
}
