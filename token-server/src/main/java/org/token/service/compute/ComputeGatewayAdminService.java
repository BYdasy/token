package org.token.service.compute;

import org.springframework.stereotype.Service;
import org.token.service.compute.ComputePaymentGateway;
import org.token.service.compute.ComputeMerchantService;
import org.token.service.compute.ComputeAgentScope;

import org.token.model.request.compute.GatewayReconciliationRequest;
import org.token.model.request.compute.DepositHashRecheckRequest;
import lombok.RequiredArgsConstructor;
import org.token.custom.LoggingAspect;
import org.token.model.response.OperationOutcome;
import org.token.exception.BusinessException;

/** 支付入口；回调通过验签及持久化后才返回成功，失败保留第三方重试能力。 */
@Service

@RequiredArgsConstructor
public class ComputeGatewayAdminService {
    private final ComputePaymentGateway gateway;

    private final ComputeAgentScope scope;
    private final ComputeMerchantService merchant;

    public OperationOutcome payout( String reference) { scope.requireGlobalScope();return OperationOutcome.success(gateway.queryPayout(reference)); }

    public OperationOutcome reconcile(  GatewayReconciliationRequest input) {
        scope.requireGlobalScope();
        if (!java.util.Set.of("DEPOSIT", "PAYOUT").contains(input.kind())) throw new BusinessException("server.compute.request.invalid");
        merchant.requireSuperTotp(input.code());
        Object result = gateway.requestReconciliation(input.reference(), input.kind());
        LoggingAspect.setLogDetails("提交支付核查：" + input.kind() + "，业务编号：" + input.reference(), 1);
        return OperationOutcome.success(result);
    }

    public OperationOutcome depositRecheckOptions() {
        return OperationOutcome.success(merchant.depositRecheckOptions());
    }

    public OperationOutcome depositRecheck(DepositHashRecheckRequest input) {
        var result = merchant.depositRecheck(input);
        LoggingAspect.setLogDetails("按交易哈希补查充值，请求编号：" + input.requestId(), 1);
        return OperationOutcome.success(result);
    }

    public OperationOutcome collection( String currency,  String network) {
        scope.requireGlobalScope();
        return OperationOutcome.success(gateway.collectionBalance(new ComputePaymentGateway.Context(currency, network)));
    }

    public OperationOutcome merchant( String currency,  String network) {
        scope.requireGlobalScope();
        return OperationOutcome.success(gateway.merchantFunding(new ComputePaymentGateway.Context(currency, network)));
    }

}
