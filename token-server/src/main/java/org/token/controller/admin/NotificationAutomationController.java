package org.token.controller.admin;

import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.impl.NotificationAutomationService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.Map;

@RestController
@RequestMapping("/api/system/notification-config/automation")
@lombok.RequiredArgsConstructor
public class NotificationAutomationController {
    private final NotificationAutomationService service;
    private final ResultFactory results;
    @GetMapping
    @PreAuthorize("hasAnyAuthority('60101','60102','super')")
    public Result settings(){return results.success(service.settings());}
    @PutMapping
    @PreAuthorize("hasAnyAuthority('60102','super')")
    @org.token.custom.LogAction(type="自动通知规则配置")
    public Result save(@RequestBody Map<String,Object> input){return results.success(service.save(input));}
}
