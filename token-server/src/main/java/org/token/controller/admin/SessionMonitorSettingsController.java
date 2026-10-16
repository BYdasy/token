package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.request.system.SessionMonitorSettingsRequest;
import org.token.model.response.Result;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.system.SessionMonitorSettingsService;
import org.token.tools.response.ResultFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system/settings/sessions/monitor")
@RequiredArgsConstructor
public class SessionMonitorSettingsController {
    private final SessionMonitorSettingsService service;
    private final ResultFactory results;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('60101','60102','super')")
    public Result get() { return results.success(service.get()); }

    @PutMapping
    @PreAuthorize("hasAnyAuthority('60102','super')")
    @LogAction(type = "登录凭据与在线监控设置")
    public Result save(@RequestBody SessionMonitorSettingsRequest input,
                       @AuthenticationPrincipal JwtUserPrincipal principal) {
        return results.success(service.save(input, principal.getUid()));
    }
}
