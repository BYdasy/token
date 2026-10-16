package org.token.controller.admin;

import jakarta.validation.Valid;
import org.token.model.request.compute.GatewayReconciliationRequest;
import org.token.model.request.compute.DepositHashRecheckRequest;
import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 支付入口；回调通过验签及持久化后才返回成功，失败保留第三方重试能力。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeGatewayAdminController {
    private final org.token.service.compute.ComputeGatewayAdminService service;
    private final ResultFactory resultFactory;

    @GetMapping("/admin/gateway/payouts/{reference}")
    @PreAuthorize("hasAnyAuthority('30200','30201','super')")
    public Result payout(@PathVariable String reference) {
        var outcome = service.payout(reference);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/admin/gateway/reconciliation")
    @PreAuthorize("hasAuthority('super')")
    @LogAction(type = "算力支付漏单核查")
    public Result reconcile(@Valid @RequestBody GatewayReconciliationRequest input) {
        var outcome = service.reconcile(input);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/admin/gateway/deposits/recheck-options")
    @PreAuthorize("hasAuthority('super')")
    public Result depositRecheckOptions() {
        var outcome = service.depositRecheckOptions();
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/admin/gateway/deposits/recheck")
    @PreAuthorize("hasAuthority('super')")
    @LogAction(type = "按交易哈希补查充值")
    public Result depositRecheck(@Valid @RequestBody DepositHashRecheckRequest input) {
        var outcome = service.depositRecheck(input);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/admin/gateway/collection-balance")
    @PreAuthorize("hasAnyAuthority('super')")
    public Result collection(@RequestParam String currency, @RequestParam String network) {
        var outcome = service.collection(currency, network);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/admin/gateway/merchant-funding")
    @PreAuthorize("hasAnyAuthority('super')")
    public Result merchant(@RequestParam String currency, @RequestParam String network) {
        var outcome = service.merchant(currency, network);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
