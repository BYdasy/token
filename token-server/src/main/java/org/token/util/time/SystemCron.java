package org.token.util.time;

import org.token.exception.BusinessException;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 按系统自然日和夏令时计算调度时间，数据库仍保存 UTC 时间。 */
public final class SystemCron {
    private SystemCron() { }

    public static LocalDateTime next(String expression, Instant after, ZoneId zone) {
        try {
            var next = CronExpression.parse(expression).next(after.atZone(zone));
            if (next == null) throw new IllegalArgumentException("定时表达式没有下一次执行时间");
            return LocalDateTime.ofInstant(next.toInstant(), ZoneOffset.UTC);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("server.compute.invalidCron");
        }
    }
}
