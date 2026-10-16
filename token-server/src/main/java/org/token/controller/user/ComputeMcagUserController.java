package org.token.controller.user;

import jakarta.validation.Valid;
import org.token.model.request.compute.McagSyncRequest;
import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputePaymentGateway;
import org.token.service.compute.ComputeMcagPaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 本人充值同步与全局财务异常查询，沿用原用户会话和数字权限。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "token.mcag.enabled", havingValue = "true")
public class ComputeMcagUserController {
    private final ComputeMcagPaymentGateway gateway;

    private final ResultFactory results;

    @PostMapping("/user/deposits/sync")
    public Result sync(@AuthenticationPrincipal ComputeMemberPrincipal member, @Valid @RequestBody McagSyncRequest request) {
        return results.success(gateway.sync(member.id(), new ComputePaymentGateway.Context(request.currency(), request.network(), request.walletType()), request.depositId()));
    }

}
