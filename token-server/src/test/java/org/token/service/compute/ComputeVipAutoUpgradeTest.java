package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 只用隔离 H2 验证开关与升级入口；不发充值、支付或通知。 */
class ComputeVipAutoUpgradeTest {
    private JdbcTemplate jdbc;
    private FixtureComputeData data;
    private TransactionTemplate transactions;
    private ComputeVipPeriodService periods;
    private ComputeCatalogService catalog;
    private ComputeJobService jobs;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:vip_switch_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        data = new FixtureComputeData(jdbc, new ObjectMapper());
        var manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        periods = new ComputeVipPeriodService(data, null);
        catalog = new ComputeCatalogService(data, mock(PasswordUtils.class), mock(ComputeCurrencyService.class));
        jobs = new ComputeJobService(data, null, Runnable::run, manager, null, periods);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");

        jdbc.execute("CREATE TABLE compute_relation_state(singleton_id INT PRIMARY KEY,version BIGINT)");
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,inviter_id BIGINT,level BIGINT,vip_started_at TIMESTAMP,vip_expires_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_level(id BIGINT PRIMARY KEY,threshold DECIMAL(28,8),enabled INT,required_direct_members INT,direct_member_deposit_threshold DECIMAL(28,8),vip_cycle_policy VARCHAR(8),vip_validity_days INT)");
        jdbc.execute("CREATE TABLE compute_wallet(member_id BIGINT,wallet_type VARCHAR(16),currency VARCHAR(16),available DECIMAL(28,8))");
        jdbc.execute("CREATE TABLE compute_deposit(id BIGINT PRIMARY KEY,member_id BIGINT,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE compute_finance_snapshot(business_type VARCHAR(16),business_id BIGINT,member_id BIGINT,usd_amount DECIMAL(38,8))");
        jdbc.update("INSERT INTO compute_relation_state VALUES(1,1)");
        jdbc.update("INSERT INTO compute_member(id,level) VALUES(1,1),(2,1)");
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'COMPUTE','USDT',10),(2,'COMPUTE','USDT',10)");
        jdbc.update("INSERT INTO compute_level VALUES(1,0,1,0,0,'RESET',0),(2,10,1,0,0,'RESET',30),(3,100,1,0,0,'KEEP',60)");
        jdbc.execute("ALTER TABLE compute_level ADD auto_upgrade_enabled TINYINT NOT NULL DEFAULT 1");
        jdbc.execute("ALTER TABLE compute_level ADD name VARCHAR(100) DEFAULT 'VIP'");
    }

    @Test void targetSwitchDefaultsOnForImmediateAndScheduledUpgrade() {
        assertTrue(automatic(2));
        assertEquals(true, transactions.execute(status -> periods.upgradeEligible(1)));
        assertTrue(jobs.upgradeMember(2));
        assertEquals(2L, level(1)); assertEquals(2L, level(2));
    }

    @Test void turningOffBlocksEveryAutomaticEntryButPreservesManualChangesAndExistingPeriods() {
        Map<String,Object> before = member(1);
        save(false);
        assertEquals(before, member(1), "切换配置不追溯变更等级或期限");
        assertEquals(false, transactions.execute(status -> periods.upgradeEligible(1)));
        assertFalse(jobs.upgradeMember(1));
        assertEquals(before, member(1));
        transactions.executeWithoutResult(status -> periods.changeLevel(1, 2));
        assertEquals(2L, level(1));
        Map<String,Object> manual = member(1);
        save(true);
        assertEquals(manual, member(1));
        assertEquals(1L, level(2), "重新开启只更新配置，不立即批量补升级");
        assertTrue(jobs.upgradeMember(2));
        assertFalse(jobs.upgradeMember(1));
        assertEquals(manual, member(1), "同级自动复核不能刷新人工建立的期限");
    }

    @Test void unrelatedSettingsPreserveDisabledSwitchAndNonBooleanInputIsRejected() {
        save(false);
        transactions.execute(status -> catalog.saveSettings(Map.of("guestEnabled", false)));
        assertEquals(false, automatic(2));
        for (Object value : List.of("true", "false", 1, List.of(true)))
            assertThrows(BusinessException.class, () -> transactions.execute(status -> catalog.saveVipSettings(Map.of("id",2,"autoUpgradeEnabled", value))));
        assertEquals(false, automatic(2));
        assertEquals(1L, level(1));
    }

    @Test void pendingAutomaticUpgradeWaitsForTheSwitchTransactionAndReadsItsCommittedValue() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        var saved = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        try {
            var changing = workers.submit(() -> transactions.execute(status -> {
                catalog.saveVipSettings(Map.of("id",2,"autoUpgradeEnabled", false));
                saved.countDown();
                try { assertTrue(commit.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return true;
            }));
            assertTrue(saved.await(5, TimeUnit.SECONDS));
            var upgrading = workers.submit(() -> { attempting.countDown(); return jobs.upgradeMember(1); });
            assertTrue(attempting.await(5, TimeUnit.SECONDS));
            commit.countDown();
            assertTrue(changing.get(10, TimeUnit.SECONDS));
            assertFalse(upgrading.get(10, TimeUnit.SECONDS));
            assertEquals(1L, level(1));
        } finally {
            commit.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void disabledIntermediateCanBeSkippedButHigherTargetStillRequiresEveryQualification() {
        save(false);
        jdbc.update("UPDATE compute_wallet SET available=100 WHERE member_id=1");
        jdbc.update("UPDATE compute_level SET required_direct_members=1,direct_member_deposit_threshold=10 WHERE id=3");
        assertFalse(jobs.upgradeMember(1));
        jdbc.update("UPDATE compute_member SET inviter_id=1 WHERE id=2");
        jdbc.update("INSERT INTO compute_deposit VALUES(1,2,'APPROVED')");
        jdbc.update("INSERT INTO compute_finance_snapshot VALUES('DEPOSIT',1,2,10)");
        assertTrue(jobs.upgradeMember(1));assertEquals(3L,level(1));
        assertFalse(jobs.upgradeMember(1));
    }

    @Test void rollingBackTargetSwitchDoesNotChangeEligibilityOrExistingPeriod() {
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            catalog.saveVipSettings(Map.of("id",2,"autoUpgradeEnabled",false));
            throw new IllegalStateException("rollback");
        }));
        assertTrue(automatic(2));assertTrue(jobs.upgradeMember(1));
    }

    private boolean automatic(long id) { return jdbc.queryForObject("SELECT auto_upgrade_enabled FROM compute_level WHERE id=?",Boolean.class,id); }
    private void save(boolean enabled) {
        transactions.execute(status -> catalog.saveVipSettings(Map.of("id",2,"autoUpgradeEnabled", enabled)));
        assertEquals(enabled, automatic(2));
    }
    private long level(long id) { return jdbc.queryForObject("SELECT level FROM compute_member WHERE id=?", Long.class, id); }
    private Map<String,Object> member(long id) { return data.one("SELECT level,vip_started_at,vip_expires_at FROM compute_member WHERE id=?", id); }
}
