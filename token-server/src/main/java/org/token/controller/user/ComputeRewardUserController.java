package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputeRewardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 奖励设置沿用基础设置数字权限，用户只读取本人规则与奖励记录。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeRewardUserController {
    private final ComputeRewardService service;

    private final ResultFactory results;
    @GetMapping("/user/team/rewards")
    public Result center(@AuthenticationPrincipal ComputeMemberPrincipal member){return results.success(service.center(member.id()));}

    @GetMapping("/user/team/reward-records")
    public Result records(@AuthenticationPrincipal ComputeMemberPrincipal member,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return results.success(service.records(member.id(),page,size));}

}
