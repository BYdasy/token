package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputeCheckinService;
import org.token.service.compute.ComputeUserFeaturesService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 复用基础设置权限，签到记录复用活动读取权限。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputePreferencesController {
    private final ComputeUserFeaturesService features;
    private final ComputeCheckinService checkin;
    private final ResultFactory results;

    @GetMapping("/user/preferences")
    public Result preferences(@AuthenticationPrincipal ComputeMemberPrincipal member) { return results.success(features.memberPreferences(member.id())); }

    @PutMapping("/user/language")
    public Result language(@AuthenticationPrincipal ComputeMemberPrincipal member, @RequestBody Map<String, String> input) { features.language(member.id(), input.get("language")); return results.success(); }

    @GetMapping("/user/checkin")
    public Result status(@AuthenticationPrincipal ComputeMemberPrincipal member) { return results.success(checkin.status(member.id())); }

    @PostMapping("/user/checkin")
    public Result checkin(@AuthenticationPrincipal ComputeMemberPrincipal member) { return results.success(checkin.checkin(member.id())); }

}
