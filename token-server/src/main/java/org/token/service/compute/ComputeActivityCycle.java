package org.token.service.compute;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

/** 活动周期沿用系统业务时区，日期边界通过时区转换为 UTC，不能固定加二十四小时。 */
final class ComputeActivityCycle {
    private ComputeActivityCycle() {}

    static LocalDateTime start(String cycle, LocalDateTime utcNow, ZoneId zone) {
        LocalDate day = utcNow.toInstant(ZoneOffset.UTC).atZone(zone).toLocalDate();
        LocalDate begin = switch (cycle) {
            case "DAY" -> day;
            case "WEEK" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "MONTH" -> day.withDayOfMonth(1);
            default -> throw new IllegalArgumentException("无效的活动重置周期");
        };
        return LocalDateTime.ofInstant(begin.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
    }
}
