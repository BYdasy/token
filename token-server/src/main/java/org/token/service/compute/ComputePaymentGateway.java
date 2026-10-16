package org.token.service.compute;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付供应商适配边界。此处是平台内部模型，不代表任何供应商的 HTTP 字段或签名协议。
 * 接入时实现本接口，校验供应商回调后再调用经过幂等控制的业务入账服务。
 */
public interface ComputePaymentGateway {
    record Context(String currency, String network, String walletType) {
        public Context(String currency, String network) { this(currency, network, "COMPUTE"); }
    }
    record Payout(String requestId, String address, BigDecimal amount, Context context) { }

    Map<String, Object> getOrCreateAddress(long memberId, Context context);
    Map<String, Object> createPayout(Payout payout);
    Map<String, Object> queryPayout(String reference);
    Map<String, Object> requestReconciliation(String reference, String kind);
    Map<String, Object> collectionBalance(Context context);
    Map<String, Object> merchantFunding(Context context);
    void acceptDepositCallback(byte[] body, Map<String, String> headers);
    default void acceptDepositCallback(byte[] body, Map<String, String> headers, String path) { acceptDepositCallback(body, headers); }
    void acceptPayoutCallback(byte[] body, Map<String, String> headers);
    default void acceptPayoutCallback(byte[] body, Map<String, String> headers, String path) { acceptPayoutCallback(body, headers); }
}
