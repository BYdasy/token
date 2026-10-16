package org.token.controller.publicapi;

import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 活动沿用数字管理权限和独立会员身份，所有归属来自服务端认证主体。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeActivityPublicController {
    private final org.token.service.compute.ComputeActivityApplicationService service;
    private final ResultFactory resultFactory;

    @GetMapping("/public/activities")
    public Result publicActivities(@AuthenticationPrincipal ComputeMemberPrincipal member, @RequestParam Map<String, String> query) {
        var outcome = service.publicActivities(member, query);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/public/activities/{id}")
    public Result detail(@AuthenticationPrincipal ComputeMemberPrincipal member, @PathVariable long id) {
        var outcome = service.detail(member, id);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

}
