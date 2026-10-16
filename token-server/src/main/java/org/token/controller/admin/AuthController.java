package org.token.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.token.model.request.auth.AdminLoginRequest;
import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 后台认证接口
 *
 * 登录成功后签发 JWT，密码使用 BCrypt 校验；已登录接口通过
 * Spring Security 的 @AuthenticationPrincipal 获取当前用户。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {
    private final org.token.service.admin.AdminAuthenticationService service;
    private final ResultFactory resultFactory;
    private final org.token.custom.security.ClientIpResolver clientIpResolver;

    @PostMapping("/auth/login")
    @LogAction(type = "用户登入")
    public Result login(
            @Valid @RequestBody AdminLoginRequest loginRequest,
            HttpServletRequest request
    ) {
        var context = new org.token.model.request.auth.AdminLoginContext(System.currentTimeMillis(),
                request.getHeader("Referer"), org.springframework.context.i18n.LocaleContextHolder.getLocale().toString(),
                org.token.tools.geo.IpUtils.clientOs(request), clientIpResolver.resolve(request));
        var outcome = service.login(loginRequest, context);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/auth/logout")
    @LogAction(type = "用户登出")
    public Result logout(@AuthenticationPrincipal JwtUserPrincipal principal, HttpServletRequest request) {
        var outcome = service.logout(principal, org.token.tools.media.SundryUtils.getToken(request));
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/auth/codes")
    public Result getCodes(@AuthenticationPrincipal JwtUserPrincipal principal) {
        var outcome = service.getCodes(principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/auth/user/info")
    public Result getUserInfo(@AuthenticationPrincipal JwtUserPrincipal principal) {
        var outcome = service.getUserInfo(principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
