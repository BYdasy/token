package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.request.system.AccountTotpRequiredRequest;
import org.token.model.request.system.AccountUidRequest;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 管理员设置账号是否必须使用验证器登录，以及重置绑定。 */
@RestController
@RequestMapping("/api/system/user_role/account/totp")
@RequiredArgsConstructor
public class AccountTotpAdminController {
    private final org.token.service.admin.AdminTotpManagementService service;
    private final ResultFactory resultFactory;

    @PostMapping("/status")
    @PreAuthorize("hasAnyAuthority('602041','super')")
    public Result status(@RequestBody AccountUidRequest request) {
        var outcome = service.status(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/require")
    @PreAuthorize("hasAnyAuthority('602042','super')")
    @LogAction(type = "账号验证器要求修改")
    public Result require(@RequestBody AccountTotpRequiredRequest request) {
        var outcome = service.require(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/reset")
    @PreAuthorize("hasAnyAuthority('602043','super')")
    @LogAction(type = "账号验证器重置")
    public Result reset(@RequestBody AccountUidRequest request) {
        var outcome = service.reset(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
