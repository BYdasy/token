package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputeAutoService;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 自动算力从登录身份获取用户，禁止用外部用户编号激活他人权益。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/user/auto-compute")
@RequiredArgsConstructor
public class ComputeAutoController {
    private final ComputeAutoService service;
    private final ResultFactory results;

    @GetMapping
    public Result records(@AuthenticationPrincipal ComputeMemberPrincipal principal,
                          @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return results.success(service.records(member(principal), page, size));
    }

    @PostMapping
    public Result activate(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestBody Map<String, Object> request) {
        return results.success(service.activate(member(principal), request));
    }

    @GetMapping("/status")
    public Result status(@AuthenticationPrincipal ComputeMemberPrincipal principal) {
        return results.success(service.status(member(principal)));
    }

    @GetMapping("/{orderNo}")
    public Result record(@AuthenticationPrincipal ComputeMemberPrincipal principal, @PathVariable String orderNo) {
        return results.success(service.record(member(principal), orderNo));
    }

    private static long member(ComputeMemberPrincipal principal) {
        if (principal == null) throw new AuthenticationCredentialsNotFoundException("需要会员登录");
        return principal.id();
    }
}
