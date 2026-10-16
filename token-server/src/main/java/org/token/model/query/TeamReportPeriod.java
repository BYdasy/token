package org.token.model.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 已由业务服务校验的团队报表时间范围；查询层按同一观察范围聚合。 */
public record TeamReportPeriod(List<LocalDate> days, LocalDateTime start, LocalDateTime end, ZoneId zone) {
    public TeamReportPeriod { days = List.copyOf(days); }
}
