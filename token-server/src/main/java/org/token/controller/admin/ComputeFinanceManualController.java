package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.custom.LoggingAspect;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeManagementService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 后台手工充提与审核使用同一数字权限；所有入账、冻结和幂等由事务业务服务处理。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin")
@RequiredArgsConstructor
public class ComputeFinanceManualController {
    private final ComputeManagementService management;
    private final ResultFactory results;
    private final org.token.service.compute.ComputeWithdrawalCoordinator withdrawals;

    @PostMapping("/deposits/manual")
    @PreAuthorize("hasAnyAuthority('30101','super')")
    @LogAction(type="算力手工充值记录")
    public Result deposit(@RequestBody Map<String,Object> request) {
        var record=management.manualDeposit(request);
        LoggingAspect.setLogDetails("添加手工充值记录，编号："+record.get("id")+"，用户："+record.get("memberId"),1);
        return results.success(record);
    }

    @PostMapping("/withdrawals/manual-quote")
    @PreAuthorize("hasAnyAuthority('30201','super')")
    public Result quote(@RequestBody Map<String,Object> request) {return results.success(management.manualWithdrawalQuote(request));}

    @PostMapping("/withdrawals/manual")
    @PreAuthorize("hasAnyAuthority('30201','super')")
    @LogAction(type="算力手工提款记录")
    public Result withdrawal(@RequestBody Map<String,Object> request) {
        var record=withdrawals.manualWithdrawal(request);
        LoggingAspect.setLogDetails("添加手工提款记录，编号："+record.get("id")+"，用户："+record.get("memberId"),1);
        return results.success(record);
    }
}
