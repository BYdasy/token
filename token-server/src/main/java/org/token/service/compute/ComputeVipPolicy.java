package org.token.service.compute;

import org.token.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import static org.token.repository.compute.ComputeData.*;

/** VIP 点击限制和额度使用同一服务端规则，不接受客户端提交收益、额度或结束时间。 */
public final class ComputeVipPolicy {
    private ComputeVipPolicy() { }

    public static LocalDateTime nextDay(LocalDateTime utc, ZoneId zone) {
        return LocalDateTime.ofInstant(utc.toInstant(ZoneOffset.UTC).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
    }

    public static LocalDateTime cooldown(LocalDateTime utc, ZoneId zone, long seconds) {
        LocalDateTime next = nextDay(utc, zone), waited = utc.plusSeconds(seconds);
        return next.isAfter(waited) ? next : waited;
    }

    public static BigDecimal income(BigDecimal principal, BigDecimal percent) {
        return principal.multiply(percent).divide(BigDecimal.valueOf(100), 8, RoundingMode.DOWN);
    }

    /** 用户 Token 收益发放比例；不能通过空值、超范围或隐式精度截断扩大/改变结算。 */
    public static BigDecimal tokenIncomePercent(Object value) {
        try {
            if (value == null || text(value).isBlank()) throw new NumberFormatException();
            BigDecimal percent = new BigDecimal(text(value));
            if (percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0 || percent.scale() > 2)
                throw new NumberFormatException();
            return percent;
        } catch (NumberFormatException exception) {
            throw new BusinessException("server.compute.v24.invalidTokenIncomePercent");
        }
    }
}
