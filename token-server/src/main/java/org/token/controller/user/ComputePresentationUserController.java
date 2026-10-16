package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputePresentationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 沿用平台配置权限与用户独立认证，只读估值不会改动资金。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputePresentationUserController {
    private final ComputePresentationService service;

    private final ResultFactory results;

    @GetMapping("/user/presentation")
    public Result presentation(@AuthenticationPrincipal ComputeMemberPrincipal member) { return results.success(service.member(member.id())); }

    @GetMapping("/user/unread-count")
    public Result unread(@AuthenticationPrincipal ComputeMemberPrincipal member) { return results.success(service.unread(member.id())); }

}
