package org.token.config.compute;

import org.token.service.compute.ComputePaymentGateway;

import org.token.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** 未接入通道时明确拒绝实际支付，避免模拟地址或回调被误当成真实资金。 */
@Configuration
@EnableConfigurationProperties(ComputeMcagProperties.class)
public class ComputeGatewayConfig {
    @Bean
    @ConditionalOnMissingBean(ComputePaymentGateway.class)
    @ConditionalOnProperty(name = "token.mcag.enabled", havingValue = "false", matchIfMissing = true)
    public ComputePaymentGateway unavailableComputePaymentGateway() {
        return new ComputePaymentGateway() {
            @Override public Map<String, Object> getOrCreateAddress(long memberId, Context context) { throw unavailable(); }
            @Override public Map<String, Object> createPayout(Payout payout) { throw unavailable(); }
            @Override public Map<String, Object> queryPayout(String reference) { throw unavailable(); }
            @Override public Map<String, Object> requestReconciliation(String reference, String kind) { throw unavailable(); }
            @Override public Map<String, Object> collectionBalance(Context context) { throw unavailable(); }
            @Override public Map<String, Object> merchantFunding(Context context) { throw unavailable(); }
            @Override public void acceptDepositCallback(byte[] body, Map<String, String> headers) { throw unavailable(); }
            @Override public void acceptPayoutCallback(byte[] body, Map<String, String> headers) { throw unavailable(); }
            private BusinessException unavailable() { return new BusinessException(503, "server.compute.gateway.notConfigured"); }
        };
    }
}
