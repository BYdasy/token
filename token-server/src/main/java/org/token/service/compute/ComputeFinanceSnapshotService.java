package org.token.service.compute;

import org.token.repository.compute.ComputeFinanceSnapshotRepository;

import org.token.repository.compute.ComputeData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.token.repository.compute.ComputeData.*;
import static org.token.service.compute.ComputeTokenAmounts.asToken;

/** 财务成功事件的不可变快照；新版复用已经固定的USD记账金额，旧版保留原统计口径。 */
@Service
public class ComputeFinanceSnapshotService {
    private final ComputeData data;
    private final ComputeFinanceSnapshotRepository repository;
    private final ComputeExchangeRateService rates;

    @Autowired
    public ComputeFinanceSnapshotService(ComputeData data, ComputeExchangeRateService rates, ComputeFinanceSnapshotRepository repository) {
        this.data = data;
        this.rates = rates;

        this.repository = repository;
    }

    public ComputeFinanceSnapshotService(ComputeData data, ComputeExchangeRateService rates) {
        this(data, rates, new ComputeFinanceSnapshotRepository(data));
    }

    public ComputeFinanceSnapshotService(ComputeData data) {
        this(data, new ComputeExchangeRateService(data, null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(String businessType, Map<String, Object> record, LocalDateTime postedAt) {
        if (!Set.of("DEPOSIT", "WITHDRAWAL").contains(businessType)) throw new IllegalArgumentException("不支持的财务快照类型");
        long id = number(record.get("id"));
        // 原订单在调用方已加行锁，重放必须保留第一次成功记账的金额和汇率。
        if (!repository.captureFinanceSnapshotRows(businessType, id).isEmpty()) return;
        String currency = text(record.get("currency"));
        boolean unified = ComputeUsdAccounting.version(record) == 2;
        var quote = unified ? data.jsonMap(record.get("accountingQuoteJson")) : rates.currentUsdQuote(currency);
        BigDecimal rate = decimal(unified ? record.get("accountingRate") : quote.get("usdRate"));
        if (unified && (!"USD".equals(record.get("accountingCurrency")) || rate.signum() <= 0
                || decimal(record.get("accountingAmount")).signum() <= 0))
            throw new org.token.exception.BusinessException("server.compute.requestConflict");
        boolean valued = rate.signum() > 0;
        BigDecimal gross = decimal(record.get("amount"));
        BigDecimal fee = "WITHDRAWAL".equals(businessType) ? decimal(record.get("fee")) : BigDecimal.ZERO;
        BigDecimal net = "WITHDRAWAL".equals(businessType) ? decimal(record.get("netAmount")) : gross;
        BigDecimal usdGross = unified ? decimal(record.get("accountingAmount")) : valued ? asToken(gross, rate) : null;
        BigDecimal usdFee = unified ? ("WITHDRAWAL".equals(businessType) ? decimal(record.get("accountingFee")) : BigDecimal.ZERO) : valued ? asToken(fee, rate) : null;
        BigDecimal usdNet = unified ? ("WITHDRAWAL".equals(businessType) ? decimal(record.get("accountingNetAmount")) : usdGross) : valued ? asToken(net, rate) : null;
        if (unified && (usdFee.signum() < 0 || usdNet.signum() < 0 || usdGross.compareTo(usdFee.add(usdNet)) != 0))
            throw new org.token.exception.BusinessException("server.compute.requestConflict");
        repository.captureFinanceSnapshotWrite(businessType, id, record.get("orderNo"), number(record.get("memberId")), currency, gross, fee, net, valued ? rate : null, usdGross, usdFee, usdNet, valued ? text(quote.get("provider")) : "UNAVAILABLE", timestamp(quote.get("sourceAt")), timestamp(quote.get("refreshedAt")), unified ? "USD_ACCOUNTING_V2" : valued ? "POSTED" : "MISSING_RATE", postedAt, now());
    }

    private static LocalDateTime timestamp(Object value) {
        return value == null || text(value).isBlank() ? null : LocalDateTime.ofInstant(Instant.parse(text(value)), ZoneOffset.UTC);
    }

    public static String summaryUnit(ComputeData data) {
        String configured = data.setting("summaryCurrency", "USDT");
        return Set.of("USD", "USDT", "USDC", "TOKEN").contains(configured) ? configured : "USD";
    }
}
