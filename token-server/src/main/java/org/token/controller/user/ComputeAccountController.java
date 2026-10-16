package org.token.controller.user;

import org.token.model.request.compute.ComputeAuthRequests;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 用户端注册、三种独立登录方法、找回、个人资料与密码操作。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeAccountController {
    private final org.token.service.compute.ComputeAccountApplicationService service;
    private final ResultFactory resultFactory;
    private final org.token.custom.security.ClientIpResolver ipResolver;

    @PostMapping("/auth/register")
    public Result register(@Valid @RequestBody ComputeAuthRequests.Register request,jakarta.servlet.http.HttpServletRequest http) {
        var outcome = service.register(request, ipResolver.resolve(http));
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/auth/login/username")
    public Result loginUsername(@Valid @RequestBody ComputeAuthRequests.Login request,jakarta.servlet.http.HttpServletRequest http) {
        var outcome = service.loginUsername(request, ipResolver.resolve(http));
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/auth/login/email")
    public Result loginEmail(@Valid @RequestBody ComputeAuthRequests.Login request,jakarta.servlet.http.HttpServletRequest http) {
        var outcome = service.loginEmail(request, ipResolver.resolve(http));
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/auth/login/phone")
    public Result loginPhone(@Valid @RequestBody ComputeAuthRequests.Login request,jakarta.servlet.http.HttpServletRequest http) {
        var outcome = service.loginPhone(request, ipResolver.resolve(http));
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/auth/forgot-password")
    public Result forgotPassword(@Valid @RequestBody ComputeAuthRequests.Recovery request) {
        var outcome = service.forgotPassword(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/auth/reset-password")
    public Result resetPassword(@Valid @RequestBody ComputeAuthRequests.Reset request) {
        var outcome = service.resetPassword(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/user/me")
    public Result me(@AuthenticationPrincipal ComputeMemberPrincipal principal) {
        var outcome = service.me(principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PutMapping("/user/profile")
    public Result profile(@AuthenticationPrincipal ComputeMemberPrincipal principal, @Valid @RequestBody ComputeAuthRequests.Profile request) {
        var outcome = service.profile(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PutMapping("/user/password")
    public Result password(@AuthenticationPrincipal ComputeMemberPrincipal principal, @Valid @RequestBody ComputeAuthRequests.Password request) {
        var outcome = service.password(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user/logout")
    public Result logout(@RequestHeader("Authorization") String authorization) {
        var outcome = service.logout(authorization);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PutMapping("/user/pay-password")
    public Result payPassword(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestBody java.util.Map<String, String> request) {
        var outcome = service.payPassword(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
