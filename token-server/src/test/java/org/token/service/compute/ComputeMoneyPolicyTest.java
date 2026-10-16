package org.token.service.compute;

import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeMoneyPolicy;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComputeMoneyPolicyTest {
    @Test
    void dailyAccrualCatchesUpAndPreservesLastDayRemainder() {
        BigDecimal principal = new BigDecimal("123.45678901"), rate = new BigDecimal("13.719281");
        BigDecimal paid = BigDecimal.ZERO;
        for (int day = 1; day <= 37; day++) {
            BigDecimal cumulative = ComputeMoneyPolicy.dailyCumulativeIncome("C", principal, rate, 37, day);
            BigDecimal due = cumulative.subtract(paid);
            assertTrue(due.signum() >= 0);
            paid = paid.add(due);
        }
        assertEquals(ComputeMoneyPolicy.dailyTotalIncome(principal, rate, 37), paid);
        assertEquals(paid, ComputeMoneyPolicy.dailyCumulativeIncome("D", principal, rate, 37, 100));
    }

    @Test
    void fourModesFollowIncomeAndPrincipalContract() {
        BigDecimal principal = new BigDecimal("100"), rate = new BigDecimal("0.1");
        for (String mode : new String[]{"A", "B"}) {
            assertEquals(0, ComputeMoneyPolicy.dailyCumulativeIncome(mode, principal, rate, 30, 29).signum());
            assertEquals(new BigDecimal("3.00000000"), ComputeMoneyPolicy.dailyCumulativeIncome(mode, principal, rate, 30, 30));
        }
        for (String mode : new String[]{"C", "D"}) assertEquals(new BigDecimal("0.10000000"), ComputeMoneyPolicy.dailyCumulativeIncome(mode, principal, rate, 30, 1));
        assertTrue(ComputeMoneyPolicy.returnsPrincipal("A")); assertTrue(ComputeMoneyPolicy.returnsPrincipal("C"));
        assertFalse(ComputeMoneyPolicy.returnsPrincipal("B")); assertFalse(ComputeMoneyPolicy.returnsPrincipal("D"));
    }

    @Test
    void retiredLowAmountFieldsDoNotChangePercentageAndMinimumStillApplies() {
        Map<String, Object> rule = ComputeData.fields("rate", "10", "fixedFee", "1", "minimumFee", "0.5", "lowAmountThreshold", "100", "lowAmountFee", "2");
        assertEquals(new BigDecimal("6.00000000"), ComputeMoneyPolicy.fee(new BigDecimal("50"), rule, 0, LocalTime.NOON));
        assertEquals(new BigDecimal("11.00000000"), ComputeMoneyPolicy.fee(new BigDecimal("100"), rule, 0, LocalTime.NOON));
        rule.put("minimumFee", "7");
        assertEquals(new BigDecimal("7.00000000"), ComputeMoneyPolicy.fee(new BigDecimal("50"), rule, 0, LocalTime.NOON));
    }

    @Test
    void weekdayWindowsUseOpeningDayAcrossMidnightAndCannotBeBypassedByFreeAllowance() {
        var rule = ComputeData.fields("rate", "1", "openWeekdays", List.of(1), "openTime", "22:00", "closeTime", "02:00", "freeCount", 10);
        // 2026-09-21 是周一；周二凌晨仍处于周一开放时段。
        assertEquals(0, ComputeMoneyPolicy.withdrawalFee(new BigDecimal("100"), rule, 0, LocalDateTime.parse("2026-09-21T22:00:00")).signum());
        assertEquals(0, ComputeMoneyPolicy.withdrawalFee(new BigDecimal("100"), rule, 0, LocalDateTime.parse("2026-09-22T01:59:59")).signum());
        for (String time : List.of("2026-09-21T01:00:00", "2026-09-21T21:59:59", "2026-09-22T02:00:00", "2026-09-22T22:00:00"))
            assertEquals("server.compute.withdrawalWindowClosed", assertThrows(BusinessException.class,
                    () -> ComputeMoneyPolicy.withdrawalFee(new BigDecimal("100"), rule, 0, LocalDateTime.parse(time))).getMessage());
        assertThrows(BusinessException.class, () -> ComputeMoneyPolicy.openWeekdays(List.of()));
        assertThrows(BusinessException.class, () -> ComputeMoneyPolicy.openWeekdays(List.of(0, 8)));
        assertEquals(List.of(1, 3), ComputeMoneyPolicy.openWeekdays("3,1,3"));
    }

    @Test
    void freeAllowanceAndCrossMidnightWindowAreHandledAtBoundary() {
        Map<String, Object> rule = ComputeData.fields("rate", "1", "freeCount", 2, "openTime", "22:00", "closeTime", "06:00", "freeStartTime", "23:00", "freeEndTime", "01:00");
        assertEquals(0, ComputeMoneyPolicy.fee(new BigDecimal("100"), rule, 0, LocalTime.of(22, 0)).signum());
        assertEquals(0, ComputeMoneyPolicy.fee(new BigDecimal("100"), rule, 3, LocalTime.MIDNIGHT).signum());
        assertEquals(new BigDecimal("1.00000000"), ComputeMoneyPolicy.fee(new BigDecimal("100"), rule, 2, LocalTime.of(1, 0)));
        assertThrows(BusinessException.class, () -> ComputeMoneyPolicy.fee(new BigDecimal("100"), rule, 0, LocalTime.of(6, 0)));
    }

    @Test
    void feeCannotConsumeTheWholeWithdrawal() {
        assertThrows(BusinessException.class, () -> ComputeMoneyPolicy.fee(new BigDecimal("1"), Map.of("minimumFee", "1"), 0, LocalTime.NOON));
    }

    @Test
    void modelFluctuationIsRepeatableAndWithinConfiguredPercent() {
        BigDecimal volatility = new BigDecimal("3.5");
        LocalDate day = LocalDate.of(2026, 9, 10);
        BigDecimal change = ComputeJobService.modelChange(1, day, volatility);
        assertEquals(change, ComputeJobService.modelChange(1, day, volatility));
        assertTrue(change.abs().compareTo(volatility) <= 0);
        assertEquals(0, ComputeJobService.modelChange(1, day, BigDecimal.ZERO).signum());
    }
}
