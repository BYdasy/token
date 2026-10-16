package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeMerchantService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 本系统商户设置入口：数字权限只授予查询；高风险操作另以超级身份和逐次 TOTP 限制。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/merchant")
@RequiredArgsConstructor
public class ComputeMerchantController {
    private final ComputeMerchantService service;
    private final ResultFactory results;

    @GetMapping("/context")
    @PreAuthorize("hasAnyAuthority('80100','80200','80201','80202','80203','80204','80300','super')")
    public Result context() { return results.success(service.context()); }

    @GetMapping("/filter-options")
    @PreAuthorize("hasAnyAuthority('super','80100','80200','80201','80203')")
    public Result filterOptions() { return results.success(service.filterOptions()); }

    @GetMapping("/energy-account")
    @PreAuthorize("hasAnyAuthority('super','80203')")
    public Result energyAccount() { return results.success(service.energyAccount()); }

    @GetMapping({"/wallets", "/destinations", "/bills"})
    @PreAuthorize("hasAnyAuthority('80200','super')")
    public Result wallets(jakarta.servlet.http.HttpServletRequest request, @RequestParam Map<String,String> query) { return results.success(service.query(resource(request), query)); }
    @GetMapping({"/fee-account", "/fee-ledger"})
    @PreAuthorize("hasAnyAuthority('80201','super')")
    public Result fees(jakarta.servlet.http.HttpServletRequest request, @RequestParam Map<String,String> query) { return results.success(service.query(resource(request), query)); }
    @GetMapping("/fee-addresses")
    @PreAuthorize("hasAnyAuthority('80201','super')")
    public Result feeAddresses() { return results.success(service.feeAddresses()); }
    @GetMapping("/connection")
    @PreAuthorize("hasAnyAuthority('80300','super')")
    public Result connection() { return results.success(service.connection()); }
    @PostMapping("/connection/check")
    @LogAction(type = "商户连接检查")
    @PreAuthorize("hasAnyAuthority('80300','super')")
    public Result checkConnection() { return results.success(service.checkConnection()); }
    @PostMapping("/address-qr")
    @PreAuthorize("hasAnyAuthority('80200','80201','80203','super')")
    public Result addressQr(@RequestBody Map<String,String> input) { return results.success(service.addressQr(input.get("address"))); }
    @GetMapping({"/batches", "/sweeps"})
    @PreAuthorize("hasAnyAuthority('80100','super')")
    public Result collections(jakarta.servlet.http.HttpServletRequest request, @RequestParam Map<String,String> query) { return results.success(service.query(resource(request), query)); }
    @GetMapping("/resources")
    @PreAuthorize("hasAnyAuthority('80203','super')")
    public Result resources(@RequestParam Map<String,String> query) { return results.success(service.query("resources", query)); }
    @GetMapping({"/notifications", "/attempts"})
    @PreAuthorize("hasAnyAuthority('80202','super')")
    public Result notifications(jakarta.servlet.http.HttpServletRequest request, @RequestParam Map<String,String> query) { return results.success(service.query(resource(request), query)); }
    @GetMapping("/rechecks")
    @PreAuthorize("hasAnyAuthority('80204','super')")
    public Result rechecks(@RequestParam Map<String,String> query) { return results.success(service.query("rechecks", query)); }

    @PostMapping("/balances/refresh")
    @PreAuthorize("hasAnyAuthority('80200','super')")
    public Result refresh(@RequestBody Map<String,String> input) { return results.success(service.refresh(input.get("kind"), input.get("reference"))); }
    @PostMapping("/fee-addresses/refresh")
    @LogAction(type = "商户积分地址刷新")
    @PreAuthorize("hasAnyAuthority('80201','super')")
    public Result refreshFeeAddress(@RequestBody Map<String,String> input) { return results.success(service.refreshFeeAddress(input.get("address_id"))); }
    @PostMapping("/rechecks")
    @PreAuthorize("hasAuthority('super')")
    @LogAction(type = "提交商户链上漏单核查")
    public Result recheck(@RequestBody Map<String,String> input) {
        var value = service.recheck(input);
        org.token.custom.LoggingAspect.setLogDetails("提交商户链上核查，商户核查编号：" + input.get("merchant_recheck_id"), 1);
        return results.success(value);
    }
    @GetMapping("/synchronization")
    @PreAuthorize("hasAnyAuthority('80300','super')")
    public Result synchronization() { return results.success(service.synchronization()); }
    @PostMapping("/synchronization/{resource}")
    @PreAuthorize("hasAnyAuthority('80300','super')")
    @LogAction(type = "同步商户远端配置快照")
    public Result synchronize(@PathVariable String resource) { return results.success(service.synchronize(resource)); }
    private static String resource(jakarta.servlet.http.HttpServletRequest request) { String path=request.getRequestURI(); return path.substring(path.lastIndexOf('/')+1); }
}
