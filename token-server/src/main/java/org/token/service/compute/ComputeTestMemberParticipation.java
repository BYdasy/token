package org.token.service.compute;

import org.token.repository.compute.ComputeData;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.token.repository.compute.ComputeData.*;

/** 测试账号恢复业务参与的固定切换点；只约束新增奖励来源，不改变人数、余额或当前资格。 */
final class ComputeTestMemberParticipation {
    static final String SETTING = "testMemberParticipationStartedAt";
    private final ComputeData data;

    ComputeTestMemberParticipation(ComputeData data) { this.data = data; }

    boolean allows(Object test, Object occurredAt) {
        if (!enabled(test)) return true;
        LocalDateTime since = since(), occurred = utc(occurredAt);
        return since != null && occurred != null && !occurred.isBefore(since);
    }

    /** 字段表达式仅由源码固定提供；首充的全历史检查仍在调用方保留。 */
    String sourceFilter(String testColumn, String occurredAt, List<Object> args) {
        return org.token.repository.compute.TestMemberSourceQuery.filter(testColumn,occurredAt,since(),args);
    }

    LocalDateTime since() { return utc(data.setting(SETTING, "")); }

    private static LocalDateTime utc(Object value) {
        if (value instanceof LocalDateTime local) return local;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof java.time.Instant instant) return LocalDateTime.ofInstant(instant,ZoneOffset.UTC);
        try { return OffsetDateTime.parse(text(value)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
        catch (java.time.DateTimeException invalid) { return null; }
    }
}
