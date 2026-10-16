package org.token.util.time;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class SystemCronTest {
    @Test void midnightUsesTheConfiguredNaturalDayAndStoresUtc() {
        assertEquals(LocalDateTime.parse("2026-09-12T16:00:00"),
                SystemCron.next("0 0 0 * * *",Instant.parse("2026-09-12T00:00:00Z"),ZoneId.of("Asia/Shanghai")));
    }

    @Test void springGapSkipsTheNonexistentLocalExecutionTime() {
        assertEquals(LocalDateTime.parse("2026-03-09T06:30:00"),
                SystemCron.next("0 30 2 * * *",Instant.parse("2026-03-08T05:00:00Z"),ZoneId.of("America/New_York")));
    }

    @Test void autumnOverlapAlwaysReturnsAFutureInstant() {
        var after=Instant.parse("2026-11-01T05:45:00Z");
        assertEquals(LocalDateTime.parse("2026-11-01T06:30:00"),
                SystemCron.next("0 30 1 * * *",after,ZoneId.of("America/New_York")));
    }

    @Test void invalidSchedulesKeepTheExistingBusinessError() {
        assertEquals("server.compute.invalidCron",assertThrows(BusinessException.class,
                ()->SystemCron.next("invalid",Instant.now(),ZoneId.of("UTC"))).getMessage());
    }
}
