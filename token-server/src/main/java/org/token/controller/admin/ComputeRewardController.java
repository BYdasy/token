package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeRewardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 奖励设置沿用基础设置数字权限，用户只读取本人规则与奖励记录。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeRewardController {
    private final ComputeRewardService service;
    private final ComputeAgentScope scope;
    private final ResultFactory results;
    @GetMapping("/admin/reward-settings")
    @PreAuthorize("hasAnyAuthority('61300','61301','super')")
    public Result settings(){scope.requireGlobalScope();return results.success(service.settings());}

    @PutMapping("/admin/reward-settings")
    @PreAuthorize("hasAnyAuthority('61301','super')")
    @LogAction(type="邀请奖励配置")
    public Result save(@RequestBody Map<String,Object> request){scope.requireGlobalScope();return results.success(service.save(request,scope.currentAdminUid()));}

}
