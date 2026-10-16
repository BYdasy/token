package org.token.service.compute;

import org.token.repository.compute.ComputeData;

import org.token.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.token.repository.compute.ComputeData.*;

/** 记账精度、四种结算模式及手续费计算的确定性规则。 */
public final class ComputeMoneyPolicy {
    private ComputeMoneyPolicy() {}

    public static boolean returnsPrincipal(String mode) { return "A".equals(mode) || "C".equals(mode); }

    /** 产品按日利率百分数计算，累计金额统一向下保留八位小数。 */
    public static BigDecimal dailyTotalIncome(BigDecimal principal, BigDecimal dailyRate, int durationDays) {
        BigDecimal income = principal.multiply(dailyRate).multiply(BigDecimal.valueOf(durationDays))
                .divide(new BigDecimal("100"), 8, RoundingMode.DOWN);
        if (income.signum() < 0 || income.precision() - income.scale() > 18)
            throw new BusinessException("server.compute.invalidAmount");
        return income;
    }

    public static BigDecimal dailyCumulativeIncome(String mode, BigDecimal principal, BigDecimal dailyRate,
                                                   int durationDays, int elapsedDays) {
        int days = Math.min(durationDays, Math.max(0, elapsedDays));
        if (("A".equals(mode) || "B".equals(mode)) && days < durationDays) return BigDecimal.ZERO;
        return dailyTotalIncome(principal, dailyRate, days);
    }

    public static BigDecimal fee(BigDecimal amount, Map<String, Object> rule, long periodCount, LocalTime time) {
        if (!within(time, text(rule.get("openTime")), text(rule.get("closeTime"))))
            throw new BusinessException("server.compute.withdrawalWindowClosed");
        long freeCount = rule.get("freeCount") == null ? 0 : number(rule.get("freeCount"));
        boolean freeTime = !text(rule.get("freeStartTime")).isEmpty() && !text(rule.get("freeEndTime")).isEmpty()
                && within(time, text(rule.get("freeStartTime")), text(rule.get("freeEndTime")));
        if (periodCount < freeCount || freeTime) return BigDecimal.ZERO.setScale(8);
        BigDecimal fee = amount.multiply(decimal(rule.get("rate"))).divide(new BigDecimal("100"), 8, RoundingMode.UP)
                .add(decimal(rule.get("fixedFee"))).max(decimal(rule.get("minimumFee")));
        if (fee.compareTo(amount) >= 0) throw new BusinessException("server.compute.feeExceedsAmount");
        return fee.setScale(8, RoundingMode.UP);
    }

    /** 提款开放日按系统日期校验；跨午夜的后半段归属于开始日，免费次数不绕过开放限制。 */
    public static BigDecimal withdrawalFee(BigDecimal amount, Map<String, Object> rule, long periodCount, LocalDateTime systemTime) {
        var openingDate = systemTime.toLocalDate();
        String start = text(rule.get("openTime")), end = text(rule.get("closeTime"));
        if (!start.isEmpty() && !end.isEmpty()) {
            LocalTime from = LocalTime.parse(start), to = LocalTime.parse(end);
            if (from.isAfter(to) && systemTime.toLocalTime().isBefore(to)) openingDate = openingDate.minusDays(1);
        }
        if (!openWeekdays(rule.get("openWeekdays")).contains(openingDate.getDayOfWeek().getValue()))
            throw new BusinessException("server.compute.withdrawalWindowClosed");
        return fee(amount, rule, periodCount, systemTime.toLocalTime());
    }

    /** ISO 星期一至星期日为 1..7；旧未配置规则默认每天开放。 */
    public static List<Integer> openWeekdays(Object value) {
        if (value == null) return List.of(1, 2, 3, 4, 5, 6, 7);
        Collection<?> input = value instanceof Collection<?> values ? values : List.of(text(value).split(",", -1));
        var days = new ArrayList<Integer>();
        for (Object item : input) {
            String day = text(item);
            if (!day.matches("[1-7]")) throw new BusinessException("server.compute.invalidTimeWindow");
            int number = Integer.parseInt(day);
            if (!days.contains(number)) days.add(number);
        }
        if (days.isEmpty()) throw new BusinessException("server.compute.invalidTimeWindow");
        days.sort(Integer::compareTo);
        return days;
    }

    static boolean within(LocalTime time, String start, String end) {
        if (start.isEmpty() || end.isEmpty()) return true;
        LocalTime from = LocalTime.parse(start), to = LocalTime.parse(end);
        if (from.equals(to)) return true;
        return from.isBefore(to) ? !time.isBefore(from) && time.isBefore(to)
                : !time.isBefore(from) || time.isBefore(to);
    }
}
