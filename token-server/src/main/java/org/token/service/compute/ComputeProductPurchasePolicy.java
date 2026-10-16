package org.token.service.compute;

import org.token.repository.compute.ComputeProductPurchasePolicyRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeTokenAmounts;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.token.repository.compute.ComputeData.*;

/** GPU 项目的购买单位与每用户每项目累计限额；调用方须先持有会员及产品行锁。 */
final class ComputeProductPurchasePolicy {
    static final long MAX_QUANTITY = 1_000_000_000L;
    private final ComputeData data;
    private final ComputeProductPurchasePolicyRepository repository;

    ComputeProductPurchasePolicy(ComputeData data) { this.data = data;
        this.repository = new ComputeProductPurchasePolicyRepository(data);
    }

    static String mode(Map<String, Object> product) {
        String mode = text(product.get("purchaseMode"));
        if (mode.isEmpty()) return "AMOUNT";
        if (!Set.of("AMOUNT", "QUANTITY").contains(mode)) throw new BusinessException("server.compute.invalidInput");
        return mode;
    }

    static long quantity(Object value) {
        long result = number(value);
        if (result < 1 || result > MAX_QUANTITY) throw new BusinessException("server.compute.v18.productQuantityInvalid");
        return result;
    }

    /** 金额及每份单价统一以 Token 输入，再复用现有原币记账精度。 */
    Map<String, Object> quote(long memberId, Map<String, Object> product, Map<String, Object> request, BigDecimal rate) {
        String mode = mode(product);
        long quantity = "QUANTITY".equals(mode) ? quantity(request.get("quantity")) : 0;
        BigDecimal unitPrice = "QUANTITY".equals(mode) ? amount(product.get("unitPrice")) : BigDecimal.ZERO;
        BigDecimal token = "QUANTITY".equals(mode)
                ? amount(unitPrice.multiply(BigDecimal.valueOf(quantity))) : amount(request.get("amount"));
        BigDecimal nativeAmount = ComputeTokenAmounts.toNative(token, rate);
        if ("AMOUNT".equals(mode) && (nativeAmount.compareTo(decimal(product.get("minAmount"))) < 0
                || nativeAmount.compareTo(decimal(product.get("maxAmount"))) > 0))
            throw new BusinessException("server.compute.amountOutsideRange");
        BigDecimal priced = ComputeTokenAmounts.asToken(nativeAmount, rate);
        Map<String, Object> quota = check(memberId, product, priced, quantity);
        return fields("purchaseMode", mode, "quantity", quantity, "unitPriceToken", unitPrice.toPlainString(),
                "amount", nativeAmount.toPlainString(), "purchaseAmountToken", priced.toPlainString(), "quota", quota);
    }

    Map<String, Object> check(long memberId, Map<String, Object> product, BigDecimal incomingToken, long incomingQuantity) {
        Map<String, Object> quota = quota(memberId, product);
        BigDecimal maximum = decimal(product.get("maxPurchaseAmount"));
        long maximumQuantity = product.get("maxPurchaseQuantity") == null ? 0 : number(product.get("maxPurchaseQuantity"));
        if (maximum.signum() > 0) {
            if (!enabled(quota.get("amountComplete")) || incomingToken == null)
                throw new BusinessException("server.compute.v18.productLimitUnvalued");
            if (decimal(quota.get("purchasedAmountToken")).add(incomingToken).compareTo(maximum) > 0)
                throw new BusinessException("server.compute.v18.productAmountLimit");
        }
        if (maximumQuantity > 0 && number(quota.get("purchasedQuantity")) + incomingQuantity > maximumQuantity)
            throw new BusinessException("server.compute.v18.productQuantityLimit");
        return quota;
    }

    private Map<String, Object> quota(long memberId, Map<String, Object> product) {
        BigDecimal total = BigDecimal.ZERO;
        long quantity = 0;
        boolean complete = true;
        // 已到期订单继续占用额度；历史金额订单没有份数快照，不能按当前单价倒算。
        for (Map<String, Object> order : repository.quotaOrderRows(memberId, product.get("id"))) {
            quantity = Math.addExact(quantity, order.get("purchaseQuantity") == null ? 0 : number(order.get("purchaseQuantity")));
            BigDecimal token = historicalToken(order);
            if (token == null) complete = false;
            else total = total.add(token);
        }
        BigDecimal maximum = decimal(product.get("maxPurchaseAmount"));
        long maximumQuantity = product.get("maxPurchaseQuantity") == null ? 0 : number(product.get("maxPurchaseQuantity"));
        return fields("maxPurchaseAmount", maximum.toPlainString(), "maxPurchaseQuantity", maximumQuantity,
                "purchasedAmountToken", complete ? total.toPlainString() : null, "purchasedQuantity", quantity,
                "remainingAmountToken", complete && maximum.signum() > 0 ? maximum.subtract(total).max(BigDecimal.ZERO).toPlainString() : null,
                "remainingQuantity", maximumQuantity > 0 ? Math.max(0, maximumQuantity - quantity) : null, "amountComplete", complete);
    }

    private BigDecimal historicalToken(Map<String, Object> order) {
        if (order.get("purchaseAmountToken") != null) return decimal(order.get("purchaseAmountToken"));
        if (order.get("productQuoteJson") != null && !text(order.get("productQuoteJson")).isBlank()) {
            Map<String, Object> snapshot = data.jsonMap(order.get("productQuoteJson"));
            BigDecimal rate = decimal(snapshot.get("assetRate"));
            if (rate.signum() > 0) return ComputeTokenAmounts.asToken(decimal(order.get("amount")), rate);
        }
        if (Set.of("USD", "USDT", "USDC", "TOKEN").contains(text(order.get("currency")))) return decimal(order.get("amount"));
        return null;
    }

}
