package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeFinanceAttentionService;
import org.token.service.compute.ComputeFinanceSoundService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 财务声音读取新订单事件；待处理关注列表保留独立入口，权限与充提列表一致。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin")
@RequiredArgsConstructor
public class ComputeFinanceAttentionController {
    private final ComputeFinanceAttentionService attention;
    private final ResultFactory results;
    private final ComputeFinanceSoundService sounds;
    private final org.token.service.compute.ComputeFinanceEventService events;

    @GetMapping("/finance-sound/runtime")
    @PreAuthorize("hasAnyAuthority('30100','30101','30200','30201','super')")
    public Result soundRuntime(@RequestParam(defaultValue="-1") long version) { return results.success(sounds.settings(version)); }

    @GetMapping("/finance-sound/settings")
    @PreAuthorize("hasAnyAuthority('60101','60102','super')")
    public Result soundSettings() { return results.success(sounds.adminSettings()); }

    @PutMapping("/finance-sound/settings")
    @PreAuthorize("hasAnyAuthority('60102','super')")
    @org.token.custom.LogAction(type="财务全局声音配置")
    public Result saveSound(@RequestBody java.util.Map<String,Object> input) {
        var value = sounds.save(input);
        org.token.custom.LoggingAspect.setLogDetails("更新全局充提声音提醒，版本：" + value.get("version"), 1);
        return results.success(value);
    }

    @GetMapping("/deposits/attention")
    @PreAuthorize("hasAnyAuthority('30100','30101','super')")
    public Result deposits() { return results.success(attention.snapshot(false)); }

    @GetMapping("/withdrawals/attention")
    @PreAuthorize("hasAnyAuthority('30200','30201','super')")
    public Result withdrawals() { return results.success(attention.snapshot(true)); }

    @GetMapping("/deposits/events")
    @PreAuthorize("hasAnyAuthority('30100','30101','super')")
    public Result depositEvents(@RequestParam(required=false) Long afterId,@RequestParam(required=false) String scopeVersion,
                                @RequestParam(defaultValue="200") int limit) { return results.success(events.events(false,afterId,scopeVersion,limit)); }

    @GetMapping("/withdrawals/events")
    @PreAuthorize("hasAnyAuthority('30200','30201','super')")
    public Result withdrawalEvents(@RequestParam(required=false) Long afterId,@RequestParam(required=false) String scopeVersion,
                                   @RequestParam(defaultValue="200") int limit) { return results.success(events.events(true,afterId,scopeVersion,limit)); }
}
