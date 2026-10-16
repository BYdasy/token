package org.token.service.compute;

import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeVipRepository;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.token.repository.compute.ComputeData.*;

/** VIP 直推门槛的同源查询；只读取成功充值的固定计价快照，不重新估值或缓存升级判断。 */
final class ComputeVipEligibility {
    private final ComputeVipRepository repository;

    ComputeVipEligibility(ComputeData data) { this(new ComputeVipRepository(data.jdbc())); }
    ComputeVipEligibility(ComputeVipRepository repository) { this.repository = repository; }

    /** 升级只取平台统一美元钱包的当前可用余额，不计冻结、展示赠送及历史充值总量。 */
    BigDecimal availableComputeBalance(long memberId) {
        var wallets = repository.availableBalance(memberId, ComputeUsdAccounting.WALLET_CURRENCY);
        return wallets.isEmpty() ? BigDecimal.ZERO : decimal(wallets.get(0).get("available")).max(BigDecimal.ZERO);
    }

    List<Map<String, Object>> levels(long memberId) {
        // 一次聚合该用户全部直推的充值，再计算各等级人数，避免逐直推成员、逐等级查库。
        // 与有效客户奖励保持一致：测试及停用直推成员均保留已完成充值事实。
        var levels = repository.eligibilityLevels(memberId);
        for (var level : levels) {
            long required = number(level.get("requiredDirectMembers"));
            long qualified = number(level.get("qualifiedDirectMembers"));
            level.put("remainingDirectMembers", Math.max(0, required - qualified));
            level.put("directMemberRequirementMet", qualified >= required);
        }
        return levels;
    }

    static Map<String, Object> highestEligible(List<Map<String, Object>> levels, long currentLevel, BigDecimal availableBalance) {
        var highest = levels.stream().filter(level -> decimal(level.get("threshold")).compareTo(availableBalance) <= 0
                        && enabled(level.getOrDefault("autoUpgradeEnabled", true))
                        && enabled(level.get("directMemberRequirementMet")))
                .max(Comparator.<Map<String, Object>, BigDecimal>comparing(level -> decimal(level.get("threshold")))
                        .thenComparingLong(level -> number(level.get("levelId")))).orElse(null);
        return highest != null && number(highest.get("levelId")) > currentLevel ? highest : null;
    }

    /** 自动入口使用等级当前锁定读，避免外层事务旧快照忽略刚提交的逐级开关。 */
    List<Map<String,Object>> automaticLevels(long memberId, List<Map<String,Object>> levels) {
        var deposits = repository.directDeposits(memberId);
        for (var level : levels) {
            long qualified = deposits.stream().filter(row -> decimal(row.get("depositToken"))
                    .compareTo(decimal(level.get("directMemberDepositThreshold"))) >= 0).count();
            level.put("qualifiedDirectMembers", qualified);
            level.put("directMemberRequirementMet", qualified >= number(level.get("requiredDirectMembers")));
        }
        return levels;
    }
}
