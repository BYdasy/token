package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 支付入口；回调通过验签及持久化后才返回成功，失败保留第三方重试能力。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeDepositGatewayController {
    private final org.token.service.compute.ComputeDepositGatewayService service;
    private final ResultFactory resultFactory;

    @GetMapping("/user/deposit-address")
    public Result address(@AuthenticationPrincipal ComputeMemberPrincipal member, @RequestParam String currency, @RequestParam String network,
                          @RequestParam(defaultValue = "COMPUTE") String walletType) {
        var outcome = service.address(member, currency, network, walletType);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
