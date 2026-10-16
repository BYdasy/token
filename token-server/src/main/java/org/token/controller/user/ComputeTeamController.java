package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputeTeamOverviewService;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 团队入口只能使用用户独立会话中的本人身份，查询参数不能切换团队所有者。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/user/team")
@RequiredArgsConstructor
public class ComputeTeamController {
    private final ComputeTeamOverviewService service;
    private final ResultFactory results;

    @GetMapping("/overview")
    public Result overview(@AuthenticationPrincipal ComputeMemberPrincipal member, @RequestParam Map<String, String> query) {
        if (member == null) throw new AuthenticationCredentialsNotFoundException("需要会员登录");
        return results.success(service.overview(member.id(), query));
    }
}
