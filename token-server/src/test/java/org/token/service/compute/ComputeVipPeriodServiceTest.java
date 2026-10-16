package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅内存数据库验证等级周期，不连接真实会员、钱包或支付接口。 */
class ComputeVipPeriodServiceTest {
    private JdbcTemplate jdbc;
    private FixtureComputeData data;
    private TransactionTemplate transactions;
    private ComputeVipPeriodService periods;

    @BeforeEach void setUp() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:vip_period_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var settings = mock(SystemSettingService.class);
        when(settings.resolveSystemTimeZone()).thenReturn("Europe/Berlin");
        periods = new ComputeVipPeriodService(data, settings);
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,level BIGINT,vip_started_at TIMESTAMP(6),vip_expires_at TIMESTAMP(6),updated_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE compute_level(id BIGINT PRIMARY KEY,enabled INT,vip_validity_days INT,vip_cycle_policy VARCHAR(8))");
        jdbc.update("INSERT INTO compute_level VALUES(1,1,0,'RESET'),(2,1,180,'RESET'),(3,1,30,'KEEP'),(4,0,7,'RESET')");
        jdbc.update("INSERT INTO compute_member(id,level) VALUES(1,1)");
    }

    @Test void changedResetLevelStartsConfiguredNaturalDayPeriod() {
        change(2);
        assertPeriod(180);
        assertEquals(2L, jdbc.queryForObject("SELECT level FROM compute_member WHERE id=1", Long.class));
    }

    @Test void keepWithoutAnyPreviousPeriodInitializesFromTargetSettings() {
        change(3);
        assertPeriod(30);
    }

    @Test void keepPreservesExistingFinitePeriodIncludingAlreadyExpiredPeriod() {
        LocalDateTime start = LocalDateTime.parse("2025-01-01T10:00:00");
        jdbc.update("UPDATE compute_member SET vip_started_at=?,vip_expires_at=? WHERE id=1", start, start.plusDays(5));
        Map<String, Object> before = period();
        change(3);
        assertEquals(before, period(), "保留策略不能隐式延长原有期限或续期已经到期的等级");
    }

    @Test void keepPreservesInitializedLifetimePeriod() {
        transactions.executeWithoutResult(status -> periods.initialize(1, 1));
        Map<String, Object> before = period();
        change(3);
        assertEquals(before, period(), "配置为保留时，原长期权益仍保持长期");
        assertNotNull(before.get("vipStartedAt"));
        assertNull(before.get("vipExpiresAt"));
    }

    @Test void unchangedLevelDoesNotRenewOrRetroactivelyInitializeHistory() {
        change(1);
        assertNull(period().get("vipStartedAt"));
        change(2);
        jdbc.update("UPDATE compute_member SET vip_expires_at=? WHERE id=1", LocalDateTime.parse("2025-02-01T00:00:00"));
        Map<String, Object> before = period();
        change(2);
        assertEquals(before, period());
    }

    @Test void zeroValidityResetIsLifetimeAndInitializationIsNotRepeated() {
        change(2);
        change(1);
        Map<String, Object> before = period();
        assertNotNull(before.get("vipStartedAt"));
        assertNull(before.get("vipExpiresAt"));
        transactions.executeWithoutResult(status -> periods.initialize(1, 2));
        assertEquals(before, period());
    }

    @Test void disabledTargetCannotChangeLevelOrPeriod() {
        Map<String, Object> before = period();
        assertThrows(BusinessException.class, () -> change(4));
        assertEquals(1L, jdbc.queryForObject("SELECT level FROM compute_member WHERE id=1", Long.class));
        assertEquals(before, period());
    }

    private void change(long level) { transactions.executeWithoutResult(status -> periods.changeLevel(1, level)); }
    private Map<String, Object> period() { return data.one("SELECT vip_started_at,vip_expires_at FROM compute_member WHERE id=1"); }
    private void assertPeriod(int days) {
        LocalDateTime start = jdbc.queryForObject("SELECT vip_started_at FROM compute_member WHERE id=1", LocalDateTime.class);
        LocalDateTime expiry = jdbc.queryForObject("SELECT vip_expires_at FROM compute_member WHERE id=1", LocalDateTime.class);
        assertNotNull(start);
        assertEquals(start.toInstant(ZoneOffset.UTC).atZone(ZoneId.of("Europe/Berlin")).plusDays(days).toInstant(), expiry.toInstant(ZoneOffset.UTC));
    }
}
