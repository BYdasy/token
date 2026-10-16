package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.system.LoginSessionSettingsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 登录时长归入基础设置，不引入第二套安全权限或用户身份。 */
@RestController
@RequestMapping("/api/system/settings/sessions")
@RequiredArgsConstructor
public class LoginSessionSettingsController {
    private final LoginSessionSettingsService service;
    private final ResultFactory results;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('60101','60102','super')")
    public Result get() { return results.success(service.get()); }

    @PutMapping
    @PreAuthorize("hasAnyAuthority('60102','super')")
    @LogAction(type = "登录空闲期限设置")
    public Result save(@RequestBody org.token.model.request.system.LoginSessionSettingsRequest input, @AuthenticationPrincipal JwtUserPrincipal principal) {
        return results.success(service.save(input.adminIdleHours(), input.userIdleHours(), principal.getUid()));
    }
}
