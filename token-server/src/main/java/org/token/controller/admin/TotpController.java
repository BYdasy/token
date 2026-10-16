package org.token.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.request.auth.TotpCodeRequest;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.TotpService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 当前账号验证器绑定、确认和状态接口。 */
@RestController
@RequestMapping("/api/auth/totp")
@RequiredArgsConstructor
public class TotpController {

    private final TotpService totpService;
    private final ResultFactory resultFactory;

    @GetMapping("/status")
    public Result status(@AuthenticationPrincipal JwtUserPrincipal principal) {
        JwtUserPrincipal user = require(principal);
        return resultFactory.success(totpService.status(user.getUid()));
    }

    @PostMapping("/setup")
    @LogAction(type = "验证器绑定发起")
    public Result setup(@AuthenticationPrincipal JwtUserPrincipal principal) {
        JwtUserPrincipal user = require(principal);
        return resultFactory.success(totpService.setup(user.getUid(), user.getUsername()));
    }

    @PostMapping("/confirm")
    @LogAction(type = "验证器绑定确认")
    public Result confirm(
            @Valid @RequestBody TotpCodeRequest request,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        totpService.confirm(require(principal).getUid(), request.getCode());
        return resultFactory.success();
    }

    @PostMapping("/disable")
    @LogAction(type = "验证器解绑")
    public Result disable(
            @Valid @RequestBody TotpCodeRequest request,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        totpService.disable(require(principal).getUid(), request.getCode());
        return resultFactory.success();
    }

    private JwtUserPrincipal require(JwtUserPrincipal principal) {
        if (principal == null || principal.getUid() == null) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Token invalid");
        }
        return principal;
    }
}
