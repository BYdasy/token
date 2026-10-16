package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;

/** 文档返点的隔离资金边界测试；仅 H2，不启动调度、网关或外部服务。 */
class ComputeVipReferralRulesTest {
    private static final LocalDateTime POSTED_AT = LocalDateTime.of(2026, 9, 22, 0, 0);
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private FixtureComputeData data;
    private ComputeRewardService rewards;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:vip_referrals_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        data = new FixtureComputeData(jdbc, new ObjectMapper());
        rewards = new ComputeRewardService(data, new ComputeMemberRelations(jdbc));
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_relation_state(singleton_id INT PRIMARY KEY,version BIGINT)");
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,username VARCHAR(80),invite_code VARCHAR(80),inviter_id BIGINT,level INT,enabled INT DEFAULT 1,is_test INT DEFAULT 0,vip_expires_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_level(id INT PRIMARY KEY,enabled INT DEFAULT 1,reward_rules_json VARCHAR(10000))");

        jdbc.execute("CREATE TABLE compute_reward_event(event_key VARCHAR(80) PRIMARY KEY,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_reward_award(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(24) UNIQUE,event_key VARCHAR(80),member_id BIGINT,source_member_id BIGINT,source_kind VARCHAR(24),source_id BIGINT,rule_key VARCHAR(80),depth INT,currency VARCHAR(16),amount DECIMAL(28,8),source_amount DECIMAL(28,8),is_virtual INT,rule_snapshot VARCHAR(10000),created_at TIMESTAMP,UNIQUE(event_key,member_id,rule_key))");
        jdbc.execute("CREATE TABLE compute_wallet(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT,wallet_type VARCHAR(16),currency VARCHAR(16),available DECIMAL(28,8),frozen DECIMAL(28,8),updated_at TIMESTAMP,UNIQUE(member_id,wallet_type,currency))");
        jdbc.execute("CREATE TABLE compute_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(24) UNIQUE,member_id BIGINT,wallet_type VARCHAR(16),currency VARCHAR(16),kind VARCHAR(32),amount DECIMAL(28,8),frozen_change DECIMAL(28,8),available_before DECIMAL(28,8),available_after DECIMAL(28,8),frozen_before DECIMAL(28,8),frozen_after DECIMAL(28,8),reference_id VARCHAR(80),event_key VARCHAR(80),created_at TIMESTAMP,UNIQUE(member_id,event_key))");
        jdbc.execute("CREATE TABLE compute_deposit(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(24) UNIQUE,member_id BIGINT,wallet_type VARCHAR(16),currency VARCHAR(16),amount DECIMAL(28,8),status VARCHAR(16),reviewed_at TIMESTAMP,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_finance_snapshot(business_type VARCHAR(16),business_id BIGINT,member_id BIGINT,usd_amount DECIMAL(28,8),PRIMARY KEY(business_type,business_id))");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_version INT DEFAULT 1");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_currency VARCHAR(16)");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_amount DECIMAL(28,8)");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_rate DECIMAL(36,18)");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_status VARCHAR(24)");
        jdbc.execute("CREATE TABLE compute_currency_asset(asset_code VARCHAR(16),sort_no INT,id INT)");
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','summaryCurrency','USDT'),('compute','teamSupportedDepth','3')");
        jdbc.update("INSERT INTO compute_relation_state VALUES(1,0)");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value,group_name,revision) VALUES('compute.reward.configuration',?,'reward',0)",
                data.json(Map.of("configuration",Map.of("depositRules",List.of(),"effectiveRules",List.of()),"activatedAt",POSTED_AT.minusDays(1).toInstant(java.time.ZoneOffset.UTC).toString())));
        jdbc.update("INSERT INTO compute_level VALUES(1,1,'[]')");
        for (int id = 1; id <= 4; id++) {
            jdbc.update("INSERT INTO compute_member(id,username,invite_code,inviter_id,level) VALUES(?,?,?,?,1)", id, "member" + id, "invite" + id, id == 1 ? null : id - 1);
            jdbc.update("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen) VALUES(?,'COMPUTE','USDT',1000,0)", id);
        }
    }

    @Test void convertedNativeDepositRebatesUseVerifiedUsdLedgerAndNeverNativeCoinCount() {
        rules(true, false, false, true, false, true);
        computeBalance(3, "100");
        long id = deposit("TRX", "300", "100", "COMPUTE", "APPROVED");
        jdbc.update("UPDATE compute_deposit SET accounting_version=2,accounting_currency='USD',accounting_amount=100,accounting_rate=0.333333333333333333,accounting_status='POSTED' WHERE id=?", id);
        // 原币账本即使金额与原充值吻合，也不能充当新版已入账USD的依据。
        assertThrows(BusinessException.class, () -> tx.executeWithoutResult(status -> rewards.onDepositApproved(id)));
        assertEquals(0, count("compute_reward_event"));
        jdbc.update("UPDATE compute_ledger SET currency='USDT',amount=100 WHERE member_id=4 AND event_key=?", "DEPOSIT:"+id);
        tx.executeWithoutResult(status -> rewards.onDepositApproved(id));
        money("15", balance(3)); money("2", balance(2)); money("1", balance(1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award WHERE currency<>'USDT'", Integer.class));
        tx.executeWithoutResult(status -> rewards.onDepositApproved(id));
        assertEquals(3, count("compute_reward_award"));
    }

    @Test void firstDepositCapsAtRecipientComputeBalanceForEveryActualDepthAndOnlyAwardsOnce() {
        rules(true, false, false, true, true, true);
        computeBalance(3, "100");
        long first = deposit("USDT", "1000", "1000", "COMPUTE", "APPROVED");
        tx.executeWithoutResult(status -> rewards.onDepositApproved(first));
        money("15", balance(3)); money("20", balance(2)); money("10", balance(1));
        var snapshot = data.jsonMap(data.one("SELECT rule_snapshot FROM compute_reward_award WHERE member_id=3").get("ruleSnapshot"));
        assertEquals("USDT", snapshot.get("basisCurrency"));
        money("100", decimal(snapshot.get("rewardBase")));
        money("1000", decimal(snapshot.get("sourceAmount")));
        tx.executeWithoutResult(status -> rewards.onDepositApproved(first));
        long second = deposit("USDT", "1000", "1000", "COMPUTE", "APPROVED");
        tx.executeWithoutResult(status -> rewards.onDepositApproved(second));
        assertEquals(3, count("compute_reward_award"));
        assertEquals(2, count("compute_reward_event"));
        money("15", balance(3));
    }

    @Test void firstSuccessfulDepositIsAcrossWalletsAndCurrenciesRatherThanFirstEligibleRetry() {
        rules(true, false, false, true, false, true);
        long earlier = deposit("USDC", "1", "1", "PROJECT", "APPROVED");
        tx.executeWithoutResult(status -> rewards.onDepositApproved(earlier));
        long later = deposit("USDT", "1000", "1000", "COMPUTE", "APPROVED");
        tx.executeWithoutResult(status -> rewards.onDepositApproved(later));
        assertEquals(0, count("compute_reward_award"));
    }

    @Test void simultaneousFirstDepositCallbacksAndLaterRetryOnlyCreditOneSetOfAwards() throws Exception {
        rules(true, false, false, true, false, true);
        computeBalance(3, "100");
        long first = deposit("USDT", "1000", "1000", "COMPUTE", "APPROVED");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Void> callback = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                tx.executeWithoutResult(status -> rewards.onDepositApproved(first));
                return null;
            };
            List<Future<Void>> callbacks = List.of(executor.submit(callback), executor.submit(callback));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var callbackResult : callbacks) callbackResult.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        tx.executeWithoutResult(status -> rewards.onDepositApproved(first));
        assertEquals(1, count("compute_reward_event"));
        assertEquals(3, count("compute_reward_award"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='TEAM_REWARD'", Integer.class));
        money("15", balance(3)); money("20", balance(2)); money("10", balance(1));
    }

    @Test void tokenIncomeRequiresAtLeastSourceComputeBalanceAndAllowsEquality() {
        rules(false, true, false, false, true, true);
        deposit("USDT", "10", "10", "COMPUTE", "APPROVED");
        computeBalance(4, "100"); computeBalance(3, "100"); computeBalance(2, "99"); computeBalance(1, "101");
        income("AUTO_INCOME", "token-equal", "10");
        money("0.3", balance(3)); money("0", balance(2)); money("0.1", balance(1));
        computeBalance(3, "99");
        income("AUTO_INCOME", "token-less", "10");
        money("0.3", balance(3));
        jdbc.update("UPDATE compute_member SET vip_expires_at=? WHERE id=1", POSTED_AT.minusYears(1));
        income("AUTO_INCOME", "token-expired", "10");
        money("0.2", balance(1));
    }

    @Test void productRebateRemainsIndependentFromTokenBalanceRestriction() {
        rules(false, false, true, false, true, false);
        computeBalance(3, "0");
        income("ORDER_INCOME", "product-income", "100");
        money("3", balance(3));
        income("AUTO_INCOME", "token-disabled", "100");
        money("3", balance(3));
        assertThrows(BusinessException.class, () -> income("ORDER_PRINCIPAL", "principal", "100"));
        money("3", balance(3));
    }

    @Test void effectiveMemberUsesFixedSuccessfulTokenAcrossCurrenciesAndNeverRevaluesMissingRows() {
        rules(false, true, false, false, false, true);
        deposit("USDT", "4", "4", "PROJECT", "APPROVED");
        deposit("USDC", "3", "3", "COMPUTE", "APPROVED");
        deposit("BTC", "100", null, "COMPUTE", "APPROVED");
        deposit("USDT", "100", "100", "COMPUTE", "PENDING");
        income("AUTO_INCOME", "under-threshold", "100");
        assertEquals(0, count("compute_reward_award"));
        deposit("BTC", "0.0001", "3", "COMPUTE", "APPROVED");
        income("AUTO_INCOME", "at-threshold", "100");
        money("3", balance(3)); money("2", balance(2)); money("1", balance(1));
        jdbc.update("UPDATE compute_deposit SET amount=amount*100");
        income("AUTO_INCOME", "fixed-snapshot", "100");
        money("6", balance(3));
        jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=4");
        income("AUTO_INCOME", "test-source", "100");
        money("6", balance(3));
        // 切换只限制新收益事件，受益资格仍认可测试来源的全历史成功充值快照。
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','testMemberParticipationStartedAt',?)", POSTED_AT.toString()+"Z");
        income("AUTO_INCOME", "test-source-after-cutover", "100");
        money("9", balance(3));
    }

    @Test void disabledBalanceRestrictionsPreserveLegacyPerDepositRatesWithoutImplicitFirstDepositAwards() {
        jdbc.update("UPDATE compute_level SET reward_rules_json=?", data.json(List.of(fields("depth", 1, "depositEnabled", true, "depositRate", "1"))));
        computeBalance(3, "0");
        long first = deposit("USDT", "100", "100", "PROJECT", "APPROVED");
        long second = deposit("USDT", "300", "300", "PROJECT", "APPROVED");
        tx.executeWithoutResult(status -> { rewards.onDepositApproved(first); rewards.onDepositApproved(second); });
        money("4", balance(3));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award WHERE source_kind='VIP_FIRST_DEPOSIT'", Integer.class));
    }

    @Test void firstAndCumulativeDepositRulesHaveIndependentSwitchesAndNeverRebateTheHistoricalTotalAgain() {
        jdbc.update("UPDATE compute_level SET reward_rules_json=?", data.json(List.of(fields("depth", 1,
                "firstDepositEnabled", true, "firstDepositRate", "15", "depositEnabled", true, "depositRate", "1",
                "depositCapByCompute", true))));
        long first = deposit("USDT", "100", "100", "COMPUTE", "APPROVED");
        tx.executeWithoutResult(status -> rewards.onDepositApproved(first));
        money("16", balance(3));
        long second = deposit("USDT", "200", "200", "COMPUTE", "APPROVED");
        tx.executeWithoutResult(status -> rewards.onDepositApproved(second));
        money("18", balance(3));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award WHERE source_kind='VIP_FIRST_DEPOSIT'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award WHERE source_kind='VIP_DEPOSIT'", Integer.class));
    }

    @Test void constrainedForeignCurrencySourcesAreNotComparedAsTokenButUnconstrainedLegacyRulesKeepTheirCurrency() {
        rules(true, false, false, true, false, true);
        long first = deposit("BTC", "100", "6000000", "COMPUTE", "APPROVED");
        tx.executeWithoutResult(status -> rewards.onDepositApproved(first));
        assertEquals(0, count("compute_reward_award"));
        jdbc.update("UPDATE compute_level SET reward_rules_json=?", data.json(List.of(fields("depth", 1,
                "depositEnabled", true, "depositRate", "2"))));
        long second = deposit("USDC", "100", "100", "PROJECT", "APPROVED");
        tx.executeWithoutResult(status -> rewards.onDepositApproved(second));
        money("2", jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=3 AND wallet_type='EARNINGS' AND currency='USDC'", BigDecimal.class));
        money("0", balance(3));
    }

    @Test void sourceLedgerMismatchCannotGenerateARewardOrMarkTheEventCompleted() {
        rules(true, false, false, true, false, false);
        long deposit = deposit("USDT", "100", "100", "COMPUTE", "APPROVED");
        jdbc.update("UPDATE compute_ledger SET amount=99 WHERE event_key=?", "DEPOSIT:" + deposit);
        assertThrows(BusinessException.class, () -> tx.executeWithoutResult(status -> rewards.onDepositApproved(deposit)));
        assertEquals(0, count("compute_reward_award"));
        assertEquals(0, count("compute_reward_event"));
    }

    @Test void newFieldsDefaultToLegacyBehaviorAndInvalidRatesAndThresholdsAreRejected() {
        var row = ComputeRewardPolicy.normalizeVipRules(List.of(fields("depth", 1, "depositEnabled", true, "depositRate", "1")), 3).get(0);
        assertEquals(false, row.get("firstDepositEnabled"));
        assertEquals(false, row.get("depositCapByCompute"));
        assertEquals(false, row.get("vipRequireComputeBalance"));
        assertEquals(false, row.get("requireEffectiveMember"));
        assertEquals("0", row.get("effectiveDepositThreshold"));
        assertThrows(BusinessException.class, () -> ComputeRewardPolicy.normalizeVipRules(List.of(fields("depth", 1, "firstDepositRate", "100.00000001")), 3));
        assertThrows(BusinessException.class, () -> ComputeRewardPolicy.normalizeVipRules(List.of(fields("depth", 1, "effectiveDepositThreshold", "-1")), 3));
    }

    private void rules(boolean first, boolean vip, boolean product, boolean cap, boolean compare, boolean effective) {
        var rows = List.of(1, 2, 3).stream().map(depth -> fields("depth", depth,
                "firstDepositEnabled", first, "firstDepositRate", depth == 1 ? "15" : depth == 2 ? "2" : "1",
                "depositEnabled", false, "depositRate", "0", "vipEnabled", vip, "vipRate", String.valueOf(4 - depth),
                "productEnabled", product, "productRate", "3", "depositCapByCompute", cap,
                "vipRequireComputeBalance", compare, "requireEffectiveMember", effective, "effectiveDepositThreshold", "10")).toList();
        jdbc.update("UPDATE compute_level SET reward_rules_json=?", data.json(ComputeRewardPolicy.normalizeVipRules(rows, 3)));
    }

    private long deposit(String currency, String amount, String token, String wallet, String state) {
        long id = data.insert("compute_deposit", fields("member_id", 4, "wallet_type", wallet, "currency", currency,
                "amount", new BigDecimal(amount), "status", state, "reviewed_at", POSTED_AT));
        if (token != null) jdbc.update("INSERT INTO compute_finance_snapshot VALUES('DEPOSIT',?,4,?)", id, new BigDecimal(token));
        if ("APPROVED".equals(state)) data.insert("compute_ledger", fields("member_id", 4, "wallet_type", wallet, "currency", currency,
                "kind", "DEPOSIT", "amount", new BigDecimal(amount), "event_key", "DEPOSIT:" + id, "created_at", POSTED_AT));
        return id;
    }

    private void income(String kind, String event, String amount) {
        tx.executeWithoutResult(status -> {
            rewards.lock();
            data.insert("compute_ledger", fields("member_id", 4, "wallet_type", "EARNINGS", "currency", "USDT",
                    "kind", kind, "amount", new BigDecimal(amount), "event_key", event, "created_at", POSTED_AT));
            rewards.onIncomePosted(4, event);
            rewards.onIncomePosted(4, event);
        });
    }

    private void computeBalance(long member, String amount) {
        jdbc.update("UPDATE compute_wallet SET available=? WHERE member_id=? AND wallet_type='COMPUTE'", new BigDecimal(amount), member);
    }
    private BigDecimal balance(long member) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(available),0) FROM compute_wallet WHERE member_id=? AND wallet_type='EARNINGS' AND currency='USDT'", BigDecimal.class, member);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void money(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
}
