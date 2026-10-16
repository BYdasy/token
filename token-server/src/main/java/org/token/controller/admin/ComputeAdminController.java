package org.token.controller.admin;

import org.token.service.compute.ComputeAccountService;
import org.token.model.request.compute.ComputeMemberCreateRequest;
import jakarta.validation.Valid;
import org.token.service.compute.ComputeCatalogService;
import org.token.service.compute.ComputeJobService;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.custom.LoggingAspect;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeManagementService;
import org.token.service.compute.ComputeHomeFinanceService;
import org.token.model.security.JwtUserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 后台所有权限直接声明数字权限码；配置与审核日志不记录密码或完整请求正文。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin")
@RequiredArgsConstructor
public class ComputeAdminController {
    private final ComputeCatalogService catalog;
    private final ComputeAccountService accounts;
    private final ComputeJobService jobs;
    private final ResultFactory results;
    private final ComputeManagementService management;
    private final ComputeHomeFinanceService homeFinance;
    private final ComputeAgentScope scope;

    @GetMapping("/overview")
    @PreAuthorize("hasAnyAuthority('10100','super')")
    public Result overview(@RequestParam Map<String, String> query) { return results.success(homeFinance.overview(query)); }

    @GetMapping("/overview/finance/{kind:DEPOSIT|WITHDRAWAL}")
    @PreAuthorize("hasAnyAuthority('10100','super')")
    public Result overviewFinance(@PathVariable String kind,@RequestParam Map<String,String> query) { return results.success(homeFinance.details(kind,query)); }

    @GetMapping("/{resource:products|models|levels|providers}")
    @PreAuthorize("(hasAnyAuthority('super')) or ((#resource == 'products' and hasAnyAuthority('70100','70101')) or (#resource == 'levels' and hasAnyAuthority('70200','70201')) or (#resource == 'models' and hasAnyAuthority('70300','70301','70200','70201')) or (#resource == 'providers' and hasAnyAuthority('70300','70301','70200','70201')))")
    public Result catalog(@PathVariable String resource) { return results.success(catalog.list(resource)); }

    @GetMapping("/levels/settings")
    @PreAuthorize("hasAnyAuthority('70200','70201','super')")
    public Result vipSettings() {
        scope.requireGlobalScope();
        return results.success(catalog.vipSettings());
    }

    @PutMapping("/levels/settings")
    @PreAuthorize("hasAnyAuthority('70201','super')")
    @LogAction(type = "VIP自动升级设置")
    public Result saveVipSettings(@RequestBody Map<String,Object> request) {
        scope.requireGlobalScope();
        return results.success(catalog.saveVipSettings(request));
    }

    @PostMapping("/{resource:products|models|levels|providers}")
    @PreAuthorize("(hasAnyAuthority('super')) or ((#resource == 'products' and hasAnyAuthority('70101')) or (#resource == 'levels' and hasAnyAuthority('70201')) or (#resource == 'models' and hasAnyAuthority('70301')) or (#resource == 'providers' and hasAnyAuthority('70301')))")
    @LogAction(type = "算力产品配置")
    public Result saveCatalog(@PathVariable String resource, @RequestBody Map<String, Object> request) {
        scope.requireGlobalScope();
        Map<String, Object> saved = catalog.save(resource, request);
        LoggingAspect.setLogDetails("保存算力配置，模块：" + resource + "，编号：" + saved.get("id"), 1);
        return results.success(saved);
    }

    @GetMapping("/{resource:fee-rules|transfer-rules}")
    @PreAuthorize("(hasAnyAuthority('super')) or ((#resource == 'fee-rules' and hasAnyAuthority('61400','61401')) or (#resource == 'transfer-rules' and hasAnyAuthority('61500','61501')))")
    public Result rules(@PathVariable String resource) { scope.requireGlobalScope(); return results.success(catalog.list(resource)); }

    @PostMapping("/{resource:fee-rules|transfer-rules}")
    @PreAuthorize("(hasAnyAuthority('super')) or ((#resource == 'fee-rules' and hasAnyAuthority('61401')) or (#resource == 'transfer-rules' and hasAnyAuthority('61501')))")
    @LogAction(type = "算力资金规则")
    public Result saveRules(@PathVariable String resource, @RequestBody Map<String, Object> request) {
        scope.requireGlobalScope();
        Map<String, Object> saved = catalog.save(resource, request);
        LoggingAspect.setLogDetails("保存算力规则，模块：" + resource + "，编号：" + saved.get("id"), 1);
        return results.success(saved);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAnyAuthority('61100','61101','super')")
    public Result settings() { scope.requireGlobalScope(); return results.success(catalog.settings()); }

    @PutMapping("/settings")
    @PreAuthorize("hasAnyAuthority('61101','super')")
    @LogAction(type = "算力平台设置")
    public Result saveSettings(@RequestBody Map<String, Object> request) {
        scope.requireGlobalScope();
        Object saved = catalog.saveSettings(request); LoggingAspect.setLogDetails("更新算力平台开关与奖励配置", 1); return results.success(saved);
    }

    @GetMapping("/members")
    @PreAuthorize("hasAnyAuthority('20100','20101','20102','20103','20104','20105','20106','super')")
    public Result members(@RequestParam Map<String, String> query) { return results.success(management.members(query)); }

    @PostMapping("/members")
    @PreAuthorize("hasAnyAuthority('20101','super')")
    @LogAction(type = "后台新增用户")
    public Result createMember(@Valid @RequestBody ComputeMemberCreateRequest request) {
        var created = accounts.createFromAdmin(request);
        LoggingAspect.setLogDetails("后台新增用户，编号：" + created.id(), 1);
        return results.success(created);
    }

    @PutMapping("/members/{id}")
    @PreAuthorize("hasAnyAuthority('20102','super')")
    @LogAction(type = "算力会员管理")
    public Result updateMember(@PathVariable long id, @RequestBody Map<String, Object> request) {
        Object saved = management.updateMember(id, request); LoggingAspect.setLogDetails("修改算力会员，编号：" + id, 1); return results.success(saved);
    }

    @GetMapping("/deposits")
    @PreAuthorize("hasAnyAuthority('30100','30101','super')")
    public Result deposits(@RequestParam Map<String, String> query) { return results.success(management.finances("deposits", query)); }

    @GetMapping("/withdrawals")
    @PreAuthorize("hasAnyAuthority('30200','30201','super')")
    public Result withdrawals(@RequestParam Map<String, String> query) { return results.success(management.finances("withdrawals", query)); }

    @GetMapping("/orders")
    @PreAuthorize("hasAnyAuthority('30300','super')")
    public Result orders(@RequestParam Map<String, String> query) { return results.success(management.finances("orders", query)); }

    @GetMapping("/ledger")
    @PreAuthorize("hasAnyAuthority('30400','super')")
    public Result ledger(@RequestParam Map<String, String> query) { return results.success(management.finances("ledger", query)); }

    @PostMapping("/deposits/{id}/review")
    @PreAuthorize("hasAnyAuthority('30101','super')")
    @LogAction(type = "算力充值审核")
    public Result reviewDeposit(@PathVariable long id, @RequestBody Map<String, Object> request, @AuthenticationPrincipal JwtUserPrincipal principal) {
        boolean approved = approved(request);
        Object saved = management.reviewDeposit(id, approved, java.util.Objects.toString(request.get("remark"), "").trim(), principal.getUid());
        LoggingAspect.setLogDetails("审核算力充值，编号：" + id + "，审核选择：" + (approved ? "通过" : "拒绝"), 1); return results.success(saved);
    }

    @PostMapping("/withdrawals/{id}/review")
    @PreAuthorize("hasAnyAuthority('30201','super')")
    @LogAction(type = "算力提款审核")
    public Result reviewWithdrawal(@PathVariable long id, @RequestBody Map<String, Object> request, @AuthenticationPrincipal JwtUserPrincipal principal) {
        boolean approved = approved(request);
        Object saved = management.reviewWithdrawal(id, approved, java.util.Objects.toString(request.get("remark"), "").trim(), principal.getUid());
        LoggingAspect.setLogDetails("审核算力提款，编号：" + id + "，审核选择：" + (approved ? "通过" : "拒绝"), 1); return results.success(saved);
    }

    @PutMapping("/withdrawals/{id}/attention")
    @PreAuthorize("hasAnyAuthority('30201','super')")
    @LogAction(type = "提款提醒设置")
    public Result withdrawalAttention(@PathVariable long id, @RequestBody Map<String,Object> request, @AuthenticationPrincipal JwtUserPrincipal principal) {
        if (!request.keySet().equals(java.util.Set.of("ignored")) || !(request.get("ignored") instanceof Boolean ignored))
            throw new BusinessException("server.compute.invalidInput");
        Object saved = management.setWithdrawalAttention(id, ignored, principal.getUid());
        LoggingAspect.setLogDetails("提款编号：" + id + "，提醒设置：" + (ignored ? "忽略" : "恢复"), 1);
        return results.success(saved);
    }

    @PostMapping("/withdrawals/{id}/direct-approve")
    @PreAuthorize("hasAnyAuthority('30202','super')")
    @LogAction(type = "算力提款直接通过")
    public Result directApproveWithdrawal(@PathVariable long id, @RequestBody Map<String,Object> request, @AuthenticationPrincipal JwtUserPrincipal principal) {
        Object saved = management.directApproveWithdrawal(id, java.util.Objects.toString(request.get("remark"), "").trim(), principal.getUid());
        LoggingAspect.setLogDetails("直接通过算力提款，编号：" + id + "，处理方式：本地直接确认", 1);
        return results.success(saved);
    }

    @GetMapping("/jobs")
    @PreAuthorize("hasAnyAuthority('61700','61701','super')")
    public Result jobs() { scope.requireGlobalScope(); return results.success(jobs.jobs()); }

    @GetMapping("/job-runs")
    @PreAuthorize("hasAnyAuthority('61700','61701','super')")
    public Result jobRuns(@RequestParam Map<String, String> query) { scope.requireGlobalScope(); return results.success(catalog.page("job-runs", null, query)); }

    @PutMapping("/jobs/{id}")
    @PreAuthorize("hasAnyAuthority('61701','super')")
    @LogAction(type = "算力任务配置")
    public Result updateJob(@PathVariable long id, @RequestBody Map<String, Object> request) {
        scope.requireGlobalScope();
        Object saved = jobs.update(id, request); LoggingAspect.setLogDetails("更新算力任务，编号：" + id, 1); return results.success(saved);
    }

    @PostMapping("/jobs/{id}/run")
    @PreAuthorize("hasAnyAuthority('61701','super')")
    @LogAction(type = "算力任务执行")
    public Result runJob(@PathVariable long id, @AuthenticationPrincipal JwtUserPrincipal principal) {
        scope.requireGlobalScope();
        Object saved = jobs.submit(id, principal.getUid(), false); LoggingAspect.setLogDetails("提交算力任务，编号：" + id, 1); return results.success(saved);
    }

    private static boolean approved(Map<String, Object> request) {
        if (!(request.get("approved") instanceof Boolean value)) throw new BusinessException("server.compute.invalidInput");
        return value;
    }
}
