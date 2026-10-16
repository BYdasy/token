package org.token.service.compute;

import org.token.repository.compute.ComputeDepositAccountingRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.token.repository.compute.ComputeData.*;

/** 充值原币事实与 USD 入账快照分离；调用方已持有会员、充值行锁并负责同事务入账。 */
final class ComputeDepositAccounting {
    private final ComputeData data;
    private final ComputeDepositAccountingRepository repository;
    private final ComputeUsdAccounting accounting;

    ComputeDepositAccounting(ComputeData data) {
        this.data = data;
        this.accounting = new ComputeUsdAccounting(data);

        this.repository = new ComputeDepositAccountingRepository(data);
    }

    /** 缺价及无法表示的金额均保留待处理订单，不能记为已入账或消耗充值幂等标记。 */
    Optional<Map<String,Object>> prepare(Map<String,Object> deposit) {
        if (!"PENDING".equals(deposit.get("status"))) throw new BusinessException("server.compute.invalidInput");
        long id = number(deposit.get("id"));
        if ("AMOUNT_PRECISION".equals(deposit.get("accountingStatus"))) return Optional.empty();
        BigDecimal nativeAmount = decimal(deposit.get("amount"));
        if (nativeAmount.signum() <= 0) throw new BusinessException("server.compute.invalidAmount");

        // 已固定的报价不能因行情刷新、回调重试或人工重复审核而重新估值。
        if (deposit.get("accountingRate") != null) {
            if (!ComputeUsdAccounting.UNIT.equals(deposit.get("accountingCurrency"))
                    || number(deposit.get("accountingVersion")) != 2
                    || !"PENDING".equals(deposit.get("accountingStatus"))
                    || deposit.get("accountingQuoteJson") == null) throw new BusinessException("server.compute.invalidInput");
            BigDecimal expected = ComputeUsdAccounting.usdAmount(nativeAmount, decimal(deposit.get("accountingRate")));
            if (expected.signum() <= 0 || expected.compareTo(decimal(deposit.get("accountingAmount"))) != 0)
                throw new BusinessException("server.compute.invalidAmount");
            return Optional.of(deposit);
        }

        Map<String,Object> quote;
        try {
            quote = accounting.strictQuote(text(deposit.get("currency")));
        } catch (BusinessException unavailable) {
            if (!"server.compute.v10.rateUnavailable".equals(unavailable.getMessage())) throw unavailable;
            repository.prepareDepositWrite(id);
            return Optional.empty();
        }

        BigDecimal rate = decimal(quote.get("usdRate"));
        var snapshot = fields("version", 2, "accountingCurrency", ComputeUsdAccounting.UNIT,
                "walletCurrency", ComputeUsdAccounting.WALLET_CURRENCY, "nativeCurrency", deposit.get("currency"),
                "nativeAmount", nativeAmount.toPlainString(), "rounding", "DOWN", "scale", 8,
                "preparedAt", now().toString());
        snapshot.putAll(quote);
        BigDecimal credited;
        try {
            credited = ComputeUsdAccounting.usdAmount(nativeAmount, rate);
            if (credited.signum() <= 0) throw new BusinessException("server.compute.invalidAmount");
        } catch (BusinessException precision) {
            if (!"server.compute.invalidAmount".equals(precision.getMessage())) throw precision;
            // 报价可用但账户精度无法表示时单独留痕，避免持续误报缺价或把原币数量当 USD。
            repository.prepareDepositWrite2(rate, data.json(snapshot), id);
            return Optional.empty();
        }
        snapshot.put("accountingAmount", credited.toPlainString());
        repository.prepareDepositWrite3(credited, rate, data.json(snapshot), id);
        return Optional.of(repository.prepareDepositRow(id));
    }

    /** MCAG 查询已通过，但本地估值尚不能入账；状态与原币事实分别保存。 */
    static String pendingReason(ComputeData data, long depositId) {
        String status = text(new ComputeDepositAccountingRepository(data).pendingReasonDepositRow(depositId).get("accountingStatus"));
        return "AMOUNT_PRECISION".equals(status) ? "ACCOUNTING_PRECISION" : "RATE_UNAVAILABLE";
    }
}
