package org.token.controller.admin;

import org.token.service.compute.ComputeManagementService;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.custom.LoggingAspect;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 数字权限控制操作能力，服务层同时检查当前代理线可见的数据行。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin")
@RequiredArgsConstructor
public class ComputeManagementController {
    private final ComputeManagementService management;
    private final ResultFactory results;
    private final org.token.service.compute.ComputeWithdrawalCoordinator withdrawals;
    private final org.token.service.compute.ComputeInviteCodeService inviteCodes;

    @PostMapping("/members/{id}/invite-code/rotate")
    @PreAuthorize("hasAnyAuthority('20102','super')")
    @LogAction(type = "用户邀请码更换")
    public Result rotateInviteCode(@PathVariable long id, @RequestBody Map<String, Object> request) {
        var result = inviteCodes.rotate(id, request);
        LoggingAspect.setLogDetails("更换用户邀请码，用户编号：" + id, 1);
        return results.success(result);
    }

    @GetMapping("/dictionaries")
    @PreAuthorize("hasAnyAuthority('10100','20100','20101','20102','20103','20104','20105','20106','20107','20200','20201','20300','20301','20302','20500','20501','30100','30101','30200','30201','30300','30400','40100','40200','50100','50101','50200','50201','50300','50301','50400','50401','70100','70101','70200','70201','70300','70301','70500','70501','70600','70601','70800','70801','70900','70901','71000','71001','61100','61101','61200','61201','61300','61301','61400','61401','61500','61501','61700','61701','super')")
    public Result dictionaries() { return results.success(management.dictionaries()); }

    @GetMapping("/groups")
    @PreAuthorize("hasAnyAuthority('20100','20101','20102','20200','20201','40100','40200','super')")
    public Result groups(@RequestParam Map<String, String> query) { return results.success(management.groups(query)); }

    @PostMapping("/groups")
    @PreAuthorize("hasAnyAuthority('20201','super')")
    @LogAction(type = "算力用户分组")
    public Result saveGroup(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = management.saveGroup(request);
        LoggingAspect.setLogDetails("保存算力用户分组，编号：" + result.get("id"), 1); return results.success(result);
    }

    @GetMapping("/agents")
    @PreAuthorize("(hasAnyAuthority('60206','super')) or (hasAnyAuthority('20100','20101','20102','20107','40100','40200','20103','20104'))")
    public Result agents(@RequestParam Map<String, String> query) { return results.success(management.agents(query)); }

    @GetMapping("/agent-bindings/context")
    @PreAuthorize("hasAnyAuthority('60206','super')")
    public Result bindingContext() { return results.success(management.bindingContext()); }

    @GetMapping("/agent-bindings")
    @PreAuthorize("hasAnyAuthority('60206','super')")
    public Result bindings(@RequestParam Map<String, String> query) { return results.success(management.bindings(query)); }

    @PostMapping("/agent-bindings")
    @PreAuthorize("hasAnyAuthority('60206','super')")
    @LogAction(type = "算力后台代理绑定")
    public Result saveBinding(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = management.saveBinding(request);
        LoggingAspect.setLogDetails("更新后台代理归属，账号编号：" + result.get("adminUid"), 1); return results.success(result);
    }

    @PostMapping("/members/group")
    @PreAuthorize("hasAnyAuthority('20102','20201','super')")
    @LogAction(type = "算力用户批量分组")
    public Result assignGroup(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = management.assignGroup(request);
        LoggingAspect.setLogDetails("批量修改用户分组，数量：" + result.get("updated"), 1); return results.success(result);
    }

    @GetMapping("/group-members")
    @PreAuthorize("hasAnyAuthority('20100','20102','20200','20201','super')")
    public Result groupMembers(@RequestParam Map<String,String> query) {return results.success(management.groupMembers(null,query));}

    @GetMapping("/groups/{id}/members")
    @PreAuthorize("hasAnyAuthority('20100','20102','20200','20201','super')")
    public Result membersInGroup(@PathVariable long id,@RequestParam Map<String,String> query) {return results.success(management.groupMembers(id,query));}

    @GetMapping("/addresses")
    @PreAuthorize("hasAnyAuthority('20300','20301','20302','super')")
    public Result addresses(@RequestParam Map<String, String> query) { return results.success(management.addresses(query)); }

    @PostMapping("/addresses")
    @PreAuthorize("hasAnyAuthority('20301','super')")
    @LogAction(type = "算力提款地址管理")
    public Result addAddress(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = withdrawals.saveManagedAddress(null, request);
        LoggingAspect.setLogDetails("新增算力提款地址，编号：" + result.get("id"), 1); return results.success(result);
    }

    @PutMapping("/addresses/{id}")
    @PreAuthorize("hasAnyAuthority('20301','super')")
    @LogAction(type = "算力提款地址管理")
    public Result updateAddress(@PathVariable long id, @RequestBody Map<String, Object> request) {
        Map<String, Object> result = withdrawals.saveManagedAddress(id, request);
        LoggingAspect.setLogDetails("更新算力提款地址，编号：" + id, 1); return results.success(result);
    }

    @DeleteMapping("/addresses/{id}")
    @PreAuthorize("hasAnyAuthority('20302','super')")
    @LogAction(type = "算力提款地址停用")
    public Result disableAddress(@PathVariable long id) {
        Map<String, Object> result = withdrawals.saveManagedAddress(id, Map.of("enabled", false));
        LoggingAspect.setLogDetails("停用算力提款地址，编号：" + id, 1); return results.success(result);
    }

    @GetMapping("/members/{id}/wallets")
    @PreAuthorize("hasAnyAuthority('20100','20103','super')")
    public Result memberWallets(@PathVariable long id) { return results.success(management.memberWallets(id)); }

    @PostMapping("/members/{id}/adjustments")
    @PreAuthorize("hasAnyAuthority('20103','super')")
    @LogAction(type="算力用户资金调整")
    public Result adjustMoney(@PathVariable long id,@RequestBody Map<String,Object> request) {
        var result=management.adjustMoney(id,request); LoggingAspect.setLogDetails("调整用户资金，用户编号："+id+"，操作编号："+result.get("operationId"),1);return results.success(result);
    }

    @PostMapping("/members/{id}/inviter-preview")
    @PreAuthorize("hasAnyAuthority('20104','super')")
    public Result previewInviter(@PathVariable long id,@RequestBody Map<String,Object> request) { return results.success(management.previewInviter(id,request)); }

    @PostMapping("/members/{id}/inviter-transfer")
    @PreAuthorize("hasAnyAuthority('20104','super')")
    @LogAction(type="算力用户邀请关系迁移")
    public Result transferInviter(@PathVariable long id,@RequestBody Map<String,Object> request) {
        var result=management.transferInviter(id,request);LoggingAspect.setLogDetails("迁移用户邀请关系，用户编号："+id+"，目标邀请人："+result.get("inviterId"),1);return results.success(result);
    }

    @GetMapping("/reports/team")
    @PreAuthorize("hasAnyAuthority('40200','super')")
    public Result teamReport(@RequestParam Map<String, String> query) { return results.success(management.teamReport(query)); }

    @GetMapping("/reports/personal")
    @PreAuthorize("hasAnyAuthority('40100','super')")
    public Result personalReport(@RequestParam Map<String,String> query) {return results.success(management.personalReport(query));}
}
