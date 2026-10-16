package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 隔离 H2 验证升级门槛、成功充值固定计价和真实直推关系；不启动调度或外部支付。 */
class ComputeVipEligibilityTest {
    private JdbcTemplate jdbc;
    private ComputeVipEligibility eligibility;
    private ComputeJobService jobs;

    @BeforeEach void setUp() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:vip_direct_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,inviter_id BIGINT,is_test INT DEFAULT 0,enabled INT DEFAULT 1,level BIGINT DEFAULT 1,vip_started_at TIMESTAMP,vip_expires_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_level(id BIGINT PRIMARY KEY,threshold DECIMAL(28,8),enabled INT DEFAULT 1,auto_upgrade_enabled TINYINT NOT NULL DEFAULT 1,required_direct_members INT DEFAULT 0,direct_member_deposit_threshold DECIMAL(28,8) DEFAULT 0,vip_cycle_policy VARCHAR(8) DEFAULT 'RESET',vip_validity_days INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE compute_deposit(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT,amount DECIMAL(28,8),currency VARCHAR(20),wallet_type VARCHAR(20),status VARCHAR(20))");
        jdbc.execute("CREATE TABLE compute_finance_snapshot(business_type VARCHAR(16),business_id BIGINT,member_id BIGINT,usd_amount DECIMAL(38,8),PRIMARY KEY(business_type,business_id))");
        jdbc.execute("CREATE TABLE compute_wallet(member_id BIGINT,wallet_type VARCHAR(16),currency VARCHAR(16),available DECIMAL(28,8),frozen DECIMAL(28,8),UNIQUE(member_id,wallet_type,currency))");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_version INT DEFAULT 1");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_amount DECIMAL(28,8)");
        jdbc.execute("CREATE TABLE compute_relation_state(singleton_id INT PRIMARY KEY,version BIGINT)");
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.update("INSERT INTO compute_relation_state VALUES(1,1)");
        jdbc.update("INSERT INTO compute_member(id,inviter_id,is_test,enabled) VALUES(1,NULL,0,1),(2,1,0,1),(3,1,0,1),(4,1,1,1),(5,2,0,1),(6,1,0,0),(7,1,0,1),(8,1,0,1),(9,1,0,1),(10,1,0,1)");
        jdbc.update("INSERT INTO compute_level(id,threshold,required_direct_members,direct_member_deposit_threshold) VALUES(1,0,0,0),(2,100,2,100),(3,200,2,200)");
        var data = new FixtureComputeData(jdbc, new ObjectMapper());
        eligibility = new ComputeVipEligibility(data);
        jobs = new ComputeJobService(data, null, Runnable::run, new DataSourceTransactionManager(source), null,
                new ComputeVipPeriodService(data, null));
    }

    @Test void countsAllDirectMembersUsingSuccessfulSnapshotTokenAcrossCurrenciesAndWallets() {
        deposit(2, "0.0001", "BTC", "PROJECT", "APPROVED", "60");
        deposit(2, "40", "USDC", "COMPUTE", "APPROVED", "40");
        deposit(3, "999999", "BTC", "EARNINGS", "APPROVED", "99.99999999");
        deposit(4, "1000", "USDT", "COMPUTE", "APPROVED", "1000"); // 测试成员同样保留当前资格。
        deposit(5, "1000", "USDT", "COMPUTE", "APPROVED", "1000"); // 间接成员不计。
        deposit(6, "200", "USDT", "EARNINGS", "APPROVED", "200"); // 停用不抹去有效充值事实。
        deposit(7, "1000", "USDT", "COMPUTE", "PENDING", "1000");
        deposit(8, "1000", "USDT", "COMPUTE", "REJECTED", "1000");
        deposit(9, "1000", "USDT", "COMPUTE", "APPROVED", null); // 缺快照不可用当前汇率补算。
        deposit(10, "1000", "USDT", "COMPUTE", "APPROVED", null);
        jdbc.update("INSERT INTO compute_finance_snapshot SELECT 'DEPOSIT',id,member_id,NULL FROM compute_deposit WHERE member_id=10");

        assertEquals(3L, level(2).get("qualifiedDirectMembers"));
        assertEquals(2L, level(3).get("qualifiedDirectMembers"));
        assertEquals(true, level(2).get("directMemberRequirementMet"));
        assertEquals(true, level(3).get("directMemberRequirementMet"));
        assertEquals(0L, level(3).get("remainingDirectMembers"));
        jdbc.update("UPDATE compute_deposit SET amount=amount*100 WHERE member_id IN(2,3)");
        assertEquals(3L, level(2).get("qualifiedDirectMembers"), "原币数据或市场行情变化不能重估成功快照");
    }

    @Test void automaticUpgradeRequiresBothFundingAndDirectCountAndRechecksCurrentRelationships() {
        deposit(1, "200", "USDT", "COMPUTE", "APPROVED", "200");
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'COMPUTE','USDT',200,0)");
        deposit(2, "100", "USDT", "PROJECT", "APPROVED", "100");
        assertFalse(jobs.upgradeMember(1));
        deposit(3, "100", "USDT", "PROJECT", "APPROVED", "100");
        assertTrue(jobs.upgradeMember(1));
        assertEquals(2L, jdbc.queryForObject("SELECT level FROM compute_member WHERE id=1", Long.class));
        assertNotNull(jdbc.queryForObject("SELECT vip_started_at FROM compute_member WHERE id=1", java.time.LocalDateTime.class));
        deposit(2, "100", "USDT", "PROJECT", "APPROVED", "100");
        deposit(3, "100", "USDT", "PROJECT", "APPROVED", "100");
        jdbc.update("UPDATE compute_member SET inviter_id=2 WHERE id=3");
        assertFalse(jobs.upgradeMember(1));
        jdbc.update("UPDATE compute_member SET inviter_id=1 WHERE id=3");
        assertTrue(jobs.upgradeMember(1));
        assertEquals(3L, jdbc.queryForObject("SELECT level FROM compute_member WHERE id=1", Long.class));
        jdbc.update("UPDATE compute_member SET is_test=1 WHERE id IN(2,3)");
        assertFalse(jobs.upgradeMember(1));
        assertEquals(3L, jdbc.queryForObject("SELECT level FROM compute_member WHERE id=1", Long.class), "升级条件改变不会自动降级");
    }

    @Test void zeroMemberRequirementKeepsExistingUpgradeBehaviorButFundingAndEnabledChecksRemain() {
        jdbc.update("UPDATE compute_level SET required_direct_members=0 WHERE id=2");
        deposit(1, "99.99999999", "USDT", "COMPUTE", "APPROVED", null);
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'COMPUTE','USDT',99.99999999,0)");
        assertFalse(jobs.upgradeMember(1));
        deposit(1, "0.00000001", "USDT", "COMPUTE", "APPROVED", null);
        jdbc.update("UPDATE compute_wallet SET available=100 WHERE member_id=1");
        jdbc.update("UPDATE compute_level SET enabled=0 WHERE id=2");
        assertFalse(jobs.upgradeMember(1));
        jdbc.update("UPDATE compute_level SET enabled=1 WHERE id=2");
        assertTrue(jobs.upgradeMember(1));
        assertEquals(2L, jdbc.queryForObject("SELECT level FROM compute_member WHERE id=1", Long.class));
    }

    @Test void upgradeUsesCurrentAvailableWalletNotHistoricalDepositsFrozenOtherWalletsOrDisplayGifts() {
        jdbc.update("UPDATE compute_level SET required_direct_members=0");
        deposit(1, "99999", "USDT", "COMPUTE", "APPROVED", "99999");
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','userPresentation','{\"displayGift\":{\"enabled\":true,\"amountToken\":99999}}')");
        assertFalse(jobs.upgradeMember(1), "已成功充值但无当前可用余额不能升级");
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'COMPUTE','USDT',99,99999),(1,'PROJECT','USDT',99999,0),(1,'COMPUTE','BTC',99999,0)");
        assertFalse(jobs.upgradeMember(1), "不计冻结、项目钱包、旧非美元资产或展示赠送");
        jdbc.update("UPDATE compute_wallet SET available=100 WHERE member_id=1 AND wallet_type='COMPUTE' AND currency='USDT'");
        assertTrue(jobs.upgradeMember(1));
        jdbc.update("UPDATE compute_wallet SET available=0 WHERE member_id=1 AND wallet_type='COMPUTE' AND currency='USDT'");
        assertFalse(jobs.upgradeMember(1));
        assertEquals(2L, jdbc.queryForObject("SELECT level FROM compute_member WHERE id=1", Long.class), "余额下降不自动降级");
        jdbc.update("INSERT INTO compute_wallet VALUES(2,'COMPUTE','USDT',200,0)");
        assertTrue(jobs.upgradeMember(2), "划转等产生的真实可用余额同样有效，不要求存在充值记录");
        assertEquals(3L, jdbc.queryForObject("SELECT level FROM compute_member WHERE id=2", Long.class));
    }

    @Test void zeroDepositThresholdStillRequiresAPositiveValuedSuccessfulDeposit() {
        jdbc.update("UPDATE compute_level SET direct_member_deposit_threshold=0 WHERE id=2");
        assertEquals(0L, level(2).get("qualifiedDirectMembers"));
        deposit(2, "1", "USDT", "PROJECT", "APPROVED", "1");
        deposit(3, "1", "USDT", "PROJECT", "APPROVED", "0");
        assertEquals(1L, level(2).get("qualifiedDirectMembers"));
    }

    private Map<String, Object> level(long levelId) {
        return eligibility.levels(1).stream().filter(row -> ((Number) row.get("levelId")).longValue() == levelId).findFirst().orElseThrow();
    }

    private void deposit(long member, String nativeAmount, String currency, String wallet, String status, String token) {
        jdbc.update("INSERT INTO compute_deposit(member_id,amount,currency,wallet_type,status) VALUES(?,?,?,?,?)", member, new BigDecimal(nativeAmount), currency, wallet, status);
        if (token != null) jdbc.update("INSERT INTO compute_finance_snapshot SELECT 'DEPOSIT',id,member_id,? FROM compute_deposit WHERE id=(SELECT MAX(id) FROM compute_deposit)", new BigDecimal(token));
    }
}
