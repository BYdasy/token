package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeCheckinService;
import org.token.service.compute.ComputeUserFeaturesService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 复用基础设置权限，签到记录复用活动读取权限。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeUserFeaturesController {
    private final ComputeUserFeaturesService features;
    private final ComputeCheckinService checkin;
    private final ResultFactory results;

    @GetMapping("/admin/user-features")
    @PreAuthorize("hasAnyAuthority('61100','61101','super')")
    public Result settings() { return results.success(features.adminSettings()); }

    @PutMapping("/admin/user-features")
    @PreAuthorize("hasAnyAuthority('61101','super')")
    @org.token.custom.LogAction(type = "用户端功能配置")
    public Result save(@RequestBody Map<String, Object> input) { features.save(input); return results.success(); }

    @PutMapping("/admin/support-settings")
    @PreAuthorize("hasAnyAuthority('61101','super')")
    @org.token.custom.LogAction(type = "客服入口配置")
    public Result support(@RequestBody Map<String,Object> input) { features.saveSupport(input); return results.success(); }

    @GetMapping("/admin/checkin-settings")
    @PreAuthorize("hasAnyAuthority('50100','50101','super')")
    public Result checkinSettings() { return results.success(features.checkinSettings()); }

    @PutMapping("/admin/checkin-settings")
    @PreAuthorize("hasAnyAuthority('50101','super')")
    @org.token.custom.LogAction(type = "签到活动配置")
    public Result saveCheckin(@RequestBody Map<String,Object> input) { features.saveCheckin(input); return results.success(); }

    @GetMapping("/admin/checkins")
    @PreAuthorize("hasAnyAuthority('50100','50101','super')")
    public Result records(@RequestParam Map<String, String> query) { return results.success(checkin.records(query)); }

}
