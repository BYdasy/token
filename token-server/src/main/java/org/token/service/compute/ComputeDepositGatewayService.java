package org.token.service.compute;

import org.springframework.stereotype.Service;
import org.token.service.compute.ComputePaymentGateway;
import org.token.service.compute.ComputeCurrencyService;

import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.model.response.OperationOutcome;

/** 支付入口；回调通过验签及持久化后才返回成功，失败保留第三方重试能力。 */
@Service

@RequiredArgsConstructor
public class ComputeDepositGatewayService {
    private final ComputePaymentGateway gateway;

    private final ComputeCurrencyService currencies;

    public OperationOutcome address( ComputeMemberPrincipal member,  String currency,  String network,
                           String walletType) {
        currencies.requireChannel(currency, network, "DEPOSIT", null, null);
        return OperationOutcome.success(gateway.getOrCreateAddress(member.id(), new ComputePaymentGateway.Context(currency, network, walletType)));
    }

}
