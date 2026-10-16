package org.token.service.compute;

import org.token.exception.ComputeExceptionHandler;

import org.token.config.compute.ComputeGatewayConfig;
import org.token.service.compute.ComputePaymentGateway;
import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 没有供应商协议与配置时，八个预留能力均不得伪造支付成功。 */
class ComputeGatewayBoundaryTest {
    @Test
    void unavailableGatewayRefusesAllEightCapabilities() {
        var gateway = new ComputeGatewayConfig().unavailableComputePaymentGateway();
        var context = new ComputePaymentGateway.Context("USDT", "TRC20");
        var payout = new ComputePaymentGateway.Payout("request-1", "address", BigDecimal.ONE, context);
        assertThrows(BusinessException.class, () -> gateway.getOrCreateAddress(1, context));
        assertThrows(BusinessException.class, () -> gateway.createPayout(payout));
        assertThrows(BusinessException.class, () -> gateway.queryPayout("reference"));
        assertThrows(BusinessException.class, () -> gateway.requestReconciliation("reference", "DEPOSIT"));
        assertThrows(BusinessException.class, () -> gateway.collectionBalance(context));
        assertThrows(BusinessException.class, () -> gateway.merchantFunding(context));
        assertThrows(BusinessException.class, () -> gateway.acceptDepositCallback(new byte[0], Map.of()));
        assertThrows(BusinessException.class, () -> gateway.acceptPayoutCallback(new byte[0], Map.of()));
    }

    @Test
    void unavailablePaymentIsNotAcknowledgedWithHttpSuccess() {
        var advice = new ComputeExceptionHandler(mock(ResultFactory.class));
        assertEquals(503, advice.business(new BusinessException(503, "server.compute.gateway.notConfigured")).getStatusCode().value());
    }
}
