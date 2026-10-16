package org.token.service.compute;

import org.token.repository.compute.ComputeRewardEligibilityRepository;

import org.token.repository.compute.ComputeData;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.token.repository.compute.ComputeData.*;

/** 分层返点条件只读取真实入账和算力钱包；不使用展示赠送，也不重新计价历史充值。 */
final class ComputeRewardEligibility {
    private final ComputeData data;
    private final ComputeRewardEligibilityRepository repository;
    private final Map<Long, BigDecimal> balances = new LinkedHashMap<>();
    private BigDecimal effectiveToken;

    ComputeRewardEligibility(ComputeData data) { this.data = data;
        this.repository = new ComputeRewardEligibilityRepository(data);
    }

    /** 关系锁在调用入口已取得；发奖前先锁会员，避免持有收益钱包后再等待划转中的会员锁。 */
    void lockMembers(long sourceMember, List<Map<String, Object>> ancestors) {
        repository.lockMembersMemberRow(sourceMember);
        for (var ancestor : ancestors)
            repository.lockMembersMemberRow(number(ancestor.get("id")));
    }

    Decision evaluate(long sourceMember, long recipient, String currency, BigDecimal sourceAmount,
                      String kind, Map<String, Object> rule) {
        Map<String, Object> facts = new LinkedHashMap<>(rule);
        facts.put("sourceAmount", sourceAmount.toPlainString());
        facts.put("currency", currency);
        boolean effective = enabled(rule.get("requireEffectiveMember"));
        boolean cap = ("firstDeposit".equals(kind) || "deposit".equals(kind)) && enabled(rule.get("depositCapByCompute"));
        boolean compare = "vip".equals(kind) && enabled(rule.get("vipRequireComputeBalance"));
        String computeCurrency = ComputeUsdAccounting.WALLET_CURRENCY;
        facts.put("basisCurrency", computeCurrency);
        // 新充值已由来源校验转为USD账本金额；旧原币事件不拿币数与USD算力余额比较。
        if ((effective || cap || compare) && !computeCurrency.equals(currency)) return new Decision(false, BigDecimal.ZERO, facts);
        if (effective) {
            if (effectiveToken == null) effectiveToken = successfulDepositToken(sourceMember);
            facts.put("effectiveDepositToken", effectiveToken.toPlainString());
            if (effectiveToken.signum() <= 0 || effectiveToken.compareTo(decimal(rule.get("effectiveDepositThreshold"))) < 0)
                return new Decision(false, BigDecimal.ZERO, facts);
        }
        BigDecimal base = sourceAmount;
        if (cap || compare) {
            BigDecimal recipientBalance = balance(recipient, computeCurrency);
            facts.put("recipientComputeBalance", recipientBalance.toPlainString());
            if (cap) base = sourceAmount.min(recipientBalance);
            if (compare) {
                BigDecimal sourceBalance = balance(sourceMember, computeCurrency);
                facts.put("sourceComputeBalance", sourceBalance.toPlainString());
                if (recipientBalance.compareTo(sourceBalance) < 0) return new Decision(false, BigDecimal.ZERO, facts);
            }
        }
        facts.put("rewardBase", base.toPlainString());
        return new Decision(true, base, facts);
    }

    private BigDecimal balance(long member, String currency) {
        return balances.computeIfAbsent(member, ignored -> {
            var rows = repository.balanceWalletRows(member, currency);
            return rows.isEmpty() ? BigDecimal.ZERO : decimal(rows.get(0).get("available")).max(BigDecimal.ZERO);
        });
    }

    private BigDecimal successfulDepositToken(long member) {
        return decimal(repository.successfulDepositTokenDepositValue(member));
    }

    record Decision(boolean eligible, BigDecimal baseAmount, Map<String, Object> snapshot) { }

}
