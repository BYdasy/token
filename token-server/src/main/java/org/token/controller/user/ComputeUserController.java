package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 用户业务端点，只从独立会员身份读取数据归属。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeUserController {
    private final org.token.service.compute.ComputeUserApplicationService service;
    private final ResultFactory resultFactory;

    @GetMapping("/user/dashboard")
    public Result dashboard(@AuthenticationPrincipal ComputeMemberPrincipal principal) {
        var outcome = service.dashboard(principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/user/wallets")
    public Result wallets(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestParam(required=false) String currency) {
        var outcome = service.wallets(principal, currency);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user/transfers")
    public Result transfer(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestBody Map<String, Object> request) {
        var outcome = service.transfer(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/user/transfers/options")
    public Result transferOptions(@AuthenticationPrincipal ComputeMemberPrincipal principal) {
        var outcome = service.transferOptions(principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user/transfers/quote")
    public Result transferQuote(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestBody Map<String,Object> request) {
        var outcome = service.transferQuote(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user/orders")
    public Result buy(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestBody Map<String, Object> request) {
        var outcome = service.buy(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user/deposits")
    public Result deposit(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestBody Map<String, Object> request) {
        var outcome = service.deposit(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user/withdrawals/quote")
    public Result quote(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestBody Map<String, Object> request) {
        var outcome = service.quote(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/user/withdrawals/balance")
    public Result withdrawalBalance(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestParam Map<String,String> request) {
        var outcome = service.withdrawalBalance(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user/withdrawals")
    public Result withdraw(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestBody Map<String, Object> request) {
        var outcome = service.withdraw(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/user/{resource:orders|deposits|withdrawals|ledger}")
    public Result page(@AuthenticationPrincipal ComputeMemberPrincipal principal, @PathVariable String resource, @RequestParam Map<String, String> query) {
        var outcome = service.page(principal, resource, query);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/user/addresses")
    public Result addresses(@AuthenticationPrincipal ComputeMemberPrincipal principal) {
        var outcome = service.addresses(principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/user/addresses/policy")
    public Result addressPolicy(@AuthenticationPrincipal ComputeMemberPrincipal principal) {
        var outcome = service.addressPolicy(principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user/addresses")
    public Result saveAddress(@AuthenticationPrincipal ComputeMemberPrincipal principal, @RequestBody Map<String, Object> request) {
        var outcome = service.saveAddress(principal, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @DeleteMapping("/user/addresses/{id}")
    public Result deleteAddress(@AuthenticationPrincipal ComputeMemberPrincipal principal, @PathVariable long id) {
        var outcome = service.deleteAddress(principal, id);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

}
