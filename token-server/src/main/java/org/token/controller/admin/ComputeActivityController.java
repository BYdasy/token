package org.token.controller.admin;

import org.token.model.security.JwtUserPrincipal;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 活动沿用数字管理权限和独立会员身份，所有归属来自服务端认证主体。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeActivityController {
    private final org.token.service.compute.ComputeActivityApplicationService service;
    private final ResultFactory resultFactory;

    @GetMapping("/admin/activities")
    @PreAuthorize("hasAnyAuthority('50200','50201','50300','50301','50400','50401','super')")
    public Result activities(@RequestParam Map<String, String> query) {
        var outcome = service.activities(query);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/admin/activities")
    @PreAuthorize("hasAnyAuthority('50201','50301','50401','super')")
    @LogAction(type = "算力活动配置")
    public Result save(@RequestBody Map<String, Object> request) {
        var outcome = service.save(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @DeleteMapping("/admin/activities/{id}")
    @PreAuthorize("hasAnyAuthority('50201','50301','50401','super')")
    @LogAction(type = "算力活动删除")
    public Result delete(@PathVariable long id) {
        var outcome = service.delete(id);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/admin/activity-participations")
    @PreAuthorize("hasAnyAuthority('50200','50201','50300','50301','50400','50401','super')")
    public Result records(@RequestParam Map<String, String> query) {
        var outcome = service.records(query);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/admin/activities/{id}/members")
    @PreAuthorize("hasAnyAuthority('50200','50201','50300','50301','50400','50401','super')")
    public Result members(@PathVariable long id,@RequestParam Map<String,String> query){
        var outcome = service.members(id, query);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/admin/activities/{id}/members/{memberId}")
    @PreAuthorize("hasAnyAuthority('50201','50301','50401','super')")
    @LogAction(type="活动会员次数调整")
    public Result adjust(@PathVariable long id,@PathVariable long memberId,@RequestBody Map<String,Object> request,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.adjust(id, memberId, request, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/admin/activities/{id}/reset-round")
    @PreAuthorize("hasAnyAuthority('50201','50301','50401','super')")
    @LogAction(type="转盘重置轮次")
    public Result reset(@PathVariable long id){
        var outcome = service.reset(id);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/admin/activity-participations/{id}/redeem")
    @PreAuthorize("hasAnyAuthority('50201','50301','50401','super')")
    @LogAction(type="转盘兑奖")
    public Result redeem(@PathVariable long id,@RequestBody Map<String,Object> request,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.redeem(id, request, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/admin/activity-statistics")
    @PreAuthorize("hasAnyAuthority('50200','50201','50300','50301','50400','50401','super')")
    public Result statistics(@RequestParam Map<String,String> query){
        var outcome = service.statistics(query);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

}
