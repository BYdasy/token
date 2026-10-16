package org.token.service.compute;

import org.junit.jupiter.api.Test;
import org.token.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputeVipPolicyTest {
    @Test void tokenIncomePercentRejectsInvalidInputsAndKeepsTwoDecimalPrecision() {
        for (Object input : Arrays.asList(null, "", " ", "NaN", "Infinity", "-0.01", "100.01", "0.001", true)) {
            var error = assertThrows(BusinessException.class, () -> ComputeVipPolicy.tokenIncomePercent(input));
            assertEquals("server.compute.v24.invalidTokenIncomePercent", error.getMessage());
        }
        assertEquals(new BigDecimal("90.25"), ComputeVipPolicy.tokenIncomePercent("90.25"));
        assertEquals(BigDecimal.ZERO, ComputeVipPolicy.tokenIncomePercent(0));
        assertEquals(new BigDecimal("100"), ComputeVipPolicy.tokenIncomePercent(100));
    }
    @Test
    void dailyResetUsesSystemMidnightAcrossDaylightSavingAndHonorsLongerCooldown() {
        ZoneId zone = ZoneId.of("America/New_York");
        LocalDateTime before = LocalDateTime.of(2026, 3, 8, 5, 0);
        assertEquals(LocalDateTime.of(2026, 3, 9, 4, 0), ComputeVipPolicy.nextDay(before, zone));
        assertEquals(before.plusHours(25), ComputeVipPolicy.cooldown(before, zone, 25 * 3600));
        LocalDateTime fall = LocalDateTime.of(2026, 11, 1, 4, 0);
        assertEquals(LocalDateTime.of(2026, 11, 2, 5, 0), ComputeVipPolicy.nextDay(fall, zone));
    }
}
