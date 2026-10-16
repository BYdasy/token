package org.token.service.compute;

import org.token.repository.compute.ComputeProductQuoteRepository;

import lombok.extern.slf4j.Slf4j;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.token.repository.compute.ComputeData.*;

/** GPU 产品报价与一次性消费；在会员锁内复核产品、汇率及累计购买限额。 */
@Service
@Slf4j
public class ComputeProductQuoteService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeProductQuoteService(ComputeData data, ComputeProductQuoteRepository repository) {
        this.data = data;
        this.repository = repository;
    }

    public ComputeProductQuoteService(ComputeData data) {
        this(data, new ComputeProductQuoteRepository(data));
    }

    private final ComputeData data;
    private final ComputeProductQuoteRepository repository;

    private void assertPurchasable(String type) {
        if (!"GPU".equals(type)) throw new BusinessException("server.compute.invalidInput");
    }

    @Transactional
    public Map<String, Object> quote(long memberId, Map<String, Object> request) {
        repository.quoteMemberRow(memberId);
        Map<String, Object> product = repository.quoteProductRow(number(request.get("productId")));
        String type = text(product.get("type"));
        assertPurchasable(type);
        String currency = text(product.get("currency"));
        if (!ComputeUsdAccounting.WALLET_CURRENCY.equals(currency))
            throw new BusinessException("server.compute.unsupportedCurrency");
        BigDecimal rate = ComputeTokenAmounts.rate(data, currency);
        LocalDateTime time = now();
        // 只清理本会员已过期的临时报价，成功订单已保留独立审计快照。
        repository.deleteMemberExpiredQuotes(memberId, time);
        if (repository.recentQuoteCount(memberId, time.minusMinutes(1)) >= 30)
            throw new BusinessException("server.compute.v10.purchaseQuoteFrequent");
        Map<String,Object> purchase = new ComputeProductPurchasePolicy(data).quote(memberId, product, request, rate);
        BigDecimal price = amount(purchase.get("amount"));
        // JSON金额保存为十进制字符串，避免通用Map反序列化成Double后丢失八位记账精度。
        Map<String, Object> snapshot = fields("product", product, "amount", price.toPlainString(),
                "debitAmount", price.toPlainString(), "assetRate", rate.toPlainString(), "quotedAt", time.toInstant(ZoneOffset.UTC).toString());
        snapshot.putAll(purchase);
        String key = UUID.randomUUID().toString();
        long id = data.insert("compute_product_quote", fields("quote_key", key, "member_id", memberId,
                "product_id", product.get("id"), "snapshot_json", data.json(snapshot), "expires_at", time.plusSeconds(60), "created_at", time));
        return publicQuote(repository.quoteRow(id), snapshot);
    }

    /** 分批清理已过期临时报价，成交快照独立保存在订单中。 */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void cleanupExpiredQuotes() {
        try {
            var ids = repository.expiredQuoteIds(now());
            if (!ids.isEmpty()) {
                repository.deleteExpiredQuotes(ids.stream().map(row -> row.get("id")).toList(), now());
            }
        } catch (org.springframework.dao.DataAccessException failure) {
            log.warn("产品临时报价清理暂未完成：{}", failure.getClass().getSimpleName());
        }
    }

    /** 调用方已持有会员、产品锁；报价一旦进入购买事务便不能被其他请求消费。 */
    Map<String, Object> consume(long memberId, Map<String, Object> request, Map<String, Object> product) {
        assertPurchasable(text(product.get("type")));
        String key = text(request.get("quoteId"));
        if (key.isEmpty()) throw new BusinessException("server.compute.v10.purchaseQuoteRequired");
        var quotes = repository.lockQuote(key, memberId);
        if (quotes.isEmpty()) throw new BusinessException("server.compute.v10.purchaseQuoteExpired");
        Map<String, Object> quote = quotes.get(0);
        if (quote.get("usedAt") != null || !timestamp(quote.get("expiresAt")).isAfter(now()))
            throw new BusinessException("server.compute.v10.purchaseQuoteExpired");
        Map<String, Object> snapshot = data.jsonMap(quote.get("snapshotJson"));
        Map<String, Object> quotedProduct = map(snapshot.get("product"));
        if (number(quote.get("productId")) != number(product.get("id")) || !sameSnapshot(quotedProduct, product))
            throw new BusinessException("server.compute.v10.purchaseQuoteChanged");
        if (ComputeTokenAmounts.rate(data, text(product.get("currency"))).compareTo(decimal(snapshot.get("assetRate"))) != 0)
            throw new BusinessException("server.compute.v10.rateChanged");
        long quantity = snapshot.get("quantity") == null ? 0 : number(snapshot.get("quantity"));
        BigDecimal token = ComputeTokenAmounts.asToken(decimal(snapshot.get("amount")), decimal(snapshot.get("assetRate")));
        new ComputeProductPurchasePolicy(data).check(memberId, product, token, quantity);
        if(repository.markConsumed(now(), quote.get("id"))!=1)throw new BusinessException("server.compute.v10.purchaseQuoteExpired");
        return snapshot;
    }

    private Map<String, Object> publicQuote(Map<String, Object> quote, Map<String, Object> snapshot) {
        Map<String, Object> product = map(snapshot.get("product"));
        BigDecimal rate = decimal(snapshot.get("assetRate"));
        BigDecimal principal = decimal(snapshot.get("amount"));
        BigDecimal minimumRate = decimal(product.get("dailyRateMin")), maximumRate = decimal(product.get("dailyRateMax"));
        int durationDays = (int) number(product.get("durationDays"));
        String settlementMode = text(product.get("settlementMode"));
        // 展示复用报价本金、固定汇率及真实累计收益算法。
        return fields("quoteId", quote.get("quoteKey"), "expiresAt", quote.get("expiresAt"), "quotedAt", quote.get("createdAt"),
                "productId", quote.get("productId"), "amount", snapshot.get("amount"),
                "priceToken", ComputeTokenAmounts.asToken(decimal(snapshot.get("amount")), rate),
                "debitToken", ComputeTokenAmounts.asToken(decimal(snapshot.get("debitAmount")), rate),
                "debitAmount", snapshot.get("debitAmount"), "currency", product.get("currency"),
                "durationDays", durationDays,
                "dailyRateMin", minimumRate, "dailyRateMax", maximumRate, "settlementMode", settlementMode,
                "expectedIncomeMinToken", ComputeTokenAmounts.asToken(ComputeMoneyPolicy.dailyTotalIncome(principal, minimumRate, durationDays), rate),
                "expectedIncomeMaxToken", ComputeTokenAmounts.asToken(ComputeMoneyPolicy.dailyTotalIncome(principal, maximumRate, durationDays), rate),
                "principalReturnToken", ComputeTokenAmounts.asToken(ComputeMoneyPolicy.returnsPrincipal(settlementMode) ? principal : BigDecimal.ZERO, rate),
                "purchaseMode", snapshot.getOrDefault("purchaseMode", "AMOUNT"), "quantity", snapshot.getOrDefault("quantity", 0),
                "unitPriceToken", snapshot.getOrDefault("unitPriceToken", "0"), "quota", snapshot.get("quota"));
    }

    Map<String, Object> map(Object value) { return data.jsonMap(data.json(value)); }
    // MySQL JSON 会调整对象键顺序；比较规范化后的对象内容，不把序列化顺序当成价格变化。
    boolean sameSnapshot(Object left, Object right) { return map(left).equals(map(right)); }
    static LocalDateTime timestamp(Object value) { return LocalDateTime.ofInstant(Instant.parse(text(value)), ZoneOffset.UTC); }
}
