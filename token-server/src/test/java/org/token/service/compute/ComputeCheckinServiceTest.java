package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证真实钱包与签到同事务、并发幂等及连续奖励；只连接内存 H2。 */
@Tag("regression")
class ComputeCheckinServiceTest {
    JdbcTemplate jdbc;
    ComputeCheckinService service;
    ComputeBusinessService business;
    TransactionTemplate tx;
    Map<String, Object> config;

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:checkin_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,enabled INT,username VARCHAR(30),is_test INT)");
        jdbc.execute("CREATE TABLE compute_wallet(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT,wallet_type VARCHAR(30),currency VARCHAR(32),available DECIMAL(28,8),frozen DECIMAL(28,8),updated_at TIMESTAMP,UNIQUE(member_id,wallet_type,currency))");
        jdbc.execute("CREATE TABLE compute_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(32) UNIQUE,member_id BIGINT,wallet_type VARCHAR(30),currency VARCHAR(32),kind VARCHAR(40),amount DECIMAL(28,8),frozen_change DECIMAL(28,8),available_after DECIMAL(28,8),frozen_after DECIMAL(28,8),reference_id VARCHAR(100),event_key VARCHAR(100) UNIQUE,created_at TIMESTAMP)");
        TokenTestSchema.create(jdbc, "compute_checkin");
        jdbc.update("INSERT INTO compute_member VALUES(1,1,'member',0),(2,1,'tester',1)");
        var data = new FixtureComputeData(jdbc,new ObjectMapper());
        var currencies = mock(ComputeCurrencyService.class); when(currencies.defaultCurrency()).thenReturn("USDT");
        business = spy(new ComputeBusinessService(data,currencies,new PasswordUtils(),mock(ComputeSystemMessageService.class)));
        var features = mock(ComputeUserFeaturesService.class);
        config = new LinkedHashMap<>(Map.of("checkinEnabled",true,"dailyReward","2","cycleDays",3,"cycleBonus","5"));
        when(features.settings()).thenAnswer(call -> config);
        var settings = mock(SystemSettingService.class); when(settings.resolveSystemTimeZone()).thenReturn("Asia/Shanghai");
        service = spy(new ComputeCheckinService(data,features,business,settings,mock(ComputeAgentScope.class)));
        doReturn(LocalDate.of(2026,9,17)).when(service).day(any());
    }

    @Test void concurrentClicksOnlyProduceOneRecordAndOneCredit() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<Object> click = () -> { start.await(); return tx.execute(s -> service.checkin(1)); };
            var first = pool.submit(click); var second = pool.submit(click); start.countDown();
            first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_checkin",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
            assertEquals(0,new BigDecimal("2").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet",BigDecimal.class)));
        } finally { pool.shutdownNow(); }
    }

    @Test void streakBonusAppliesAtCycleBoundaryAndTestUsersKeepRecords() {
        for (int day=17;day<=19;day++) {
            doReturn(LocalDate.of(2026,9,day)).when(service).day(any());
            tx.execute(s -> service.checkin(2));
        }
        assertEquals(0,new BigDecimal("11").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=2",BigDecimal.class)));
        assertEquals(3,jdbc.queryForObject("SELECT MAX(streak_days) FROM compute_checkin",Integer.class));
        doReturn(LocalDate.of(2026,9,21)).when(service).day(any());
        tx.execute(s -> service.checkin(2));
        assertEquals(1,jdbc.queryForObject("SELECT streak_days FROM compute_checkin ORDER BY id DESC LIMIT 1",Integer.class));
    }

    @Test void walletFailureRollsBackRecordAndDisabledFeatureDoesNotPay() {
        doThrow(new BusinessException("server.compute.saveFailed")).when(business).changeWallet(anyLong(),anyString(),anyString(),any(),any(),anyString(),anyString(),anyString());
        assertThrows(BusinessException.class, () -> tx.execute(s -> service.checkin(1)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_checkin",Integer.class));
        config.put("checkinEnabled",false);
        assertThrows(BusinessException.class, () -> service.status(1));
        assertThrows(BusinessException.class, () -> tx.execute(s -> service.checkin(1)));
    }

    @Test void zeroRewardDoesNotRequireAnExchangeRateOrCreateLedgerMoney() {
        config.put("dailyReward", "0"); config.put("cycleBonus", "0");
        doReturn("BTC").when(business).defaultCurrency();
        tx.execute(s -> service.checkin(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_checkin", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT asset_rate FROM compute_checkin", BigDecimal.class).signum());
    }
}
