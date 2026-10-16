package org.token.service.compute;

import org.token.exception.BusinessException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.List;

/** 活动金额全部按八位小数的整数单位运算，抽奖不使用浮点随机概率。 */
public final class ComputeActivityRules {
    private static final SecureRandom RANDOM = new SecureRandom();
    private ComputeActivityRules() {}

    public record Prize(long id, String name, BigDecimal amount, long weight, long remainingStock) {}

    public static BigDecimal nonnegative(Object value) {
        try {
            BigDecimal amount = new BigDecimal(String.valueOf(value));
            if (amount.signum() < 0 || amount.scale() > 8 || amount.precision() - amount.scale() > 18) throw new ArithmeticException();
            return amount.setScale(8, RoundingMode.UNNECESSARY);
        } catch (RuntimeException error) { throw new BusinessException("server.compute.activity.invalidAmount"); }
    }

    public static void validatePacket(BigDecimal remainingBudget, long remainingStock, BigDecimal minimum, BigDecimal maximum) {
        if (remainingStock < 0 || minimum.signum() <= 0 || maximum.compareTo(minimum) < 0
                || remainingBudget.compareTo(minimum.multiply(BigDecimal.valueOf(remainingStock))) < 0
                || remainingBudget.compareTo(maximum.multiply(BigDecimal.valueOf(remainingStock))) > 0)
            throw new BusinessException("server.compute.activity.packetBudgetInvalid");
    }

    /** 宝箱独立随机金额，没有总预算分摊；抽样使用精确八位最小单位。 */
    public static BigDecimal rangeAmount(BigDecimal minimum,BigDecimal maximum) {
        minimum=nonnegative(minimum);maximum=nonnegative(maximum);
        if(minimum.signum()<=0||maximum.compareTo(minimum)<0)throw new BusinessException("server.compute.activity.invalidAmount");
        BigInteger lower=minimum.movePointRight(8).toBigIntegerExact();
        BigInteger choices=maximum.movePointRight(8).toBigIntegerExact().subtract(lower).add(BigInteger.ONE);
        BigInteger sampled;do {sampled=new BigInteger(choices.bitLength(),RANDOM);}while(sampled.compareTo(choices)>=0);
        return new BigDecimal(lower.add(sampled),8);
    }

    /** 每份领取后仍为剩余份数保留合法预算，最后一份得到精确结余。 */
    public static BigDecimal packetAmount(BigDecimal remainingBudget, long remainingStock, BigDecimal minimum, BigDecimal maximum) {
        validatePacket(remainingBudget, remainingStock, minimum, maximum);
        if (remainingStock == 0) throw new BusinessException("server.compute.activity.exhausted");
        BigDecimal left = BigDecimal.valueOf(remainingStock - 1);
        BigDecimal low = minimum.max(remainingBudget.subtract(maximum.multiply(left)));
        BigDecimal high = maximum.min(remainingBudget.subtract(minimum.multiply(left)));
        BigInteger lower = low.movePointRight(8).toBigIntegerExact();
        BigInteger choices = high.movePointRight(8).toBigIntegerExact().subtract(lower).add(BigInteger.ONE);
        BigInteger sampled;
        do { sampled = new BigInteger(choices.bitLength(), RANDOM); } while (sampled.compareTo(choices) >= 0);
        return new BigDecimal(lower.add(sampled), 8);
    }

    /** 只在仍有库存且预算可支付的奖项间按配置权重抽取。 */
    public static Prize choosePrize(List<Prize> prizes, BigDecimal remainingBudget) {
        List<Prize> available = prizes.stream().filter(prize -> prize.remainingStock > 0 && prize.weight > 0
                && prize.amount.signum() >= 0 && prize.amount.compareTo(remainingBudget) <= 0).toList();
        long total = available.stream().mapToLong(Prize::weight).sum();
        if (total <= 0) throw new BusinessException("server.compute.activity.exhausted");
        long selected = RANDOM.nextLong(total);
        for (Prize prize : available) {
            selected -= prize.weight;
            if (selected < 0) return prize;
        }
        throw new BusinessException("server.compute.activity.exhausted");
    }
}
