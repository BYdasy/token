package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 两个服务实例共用隔离 H2 数据库，验证并发提交和结果结算的真实行锁与账本事务。 */
@Tag("regression")
class ComputeAutoConcurrencyTest {
    @Test void memberPercentageChangesOnlyNewTokenIncomeAndReplayRetainsPaidSnapshot() {
        var fixture = discountFixture();
        fixture.jdbc.update("UPDATE compute_member SET token_income_percent=90 WHERE id=1");
        var request = Map.<String, Object>of("requestId", "discounted-token-001");
        doAnswer(invocation -> {
            String event = invocation.getArgument(1);
            assertEquals(0, new BigDecimal("90").compareTo(fixture.jdbc.queryForObject(
                    "SELECT amount FROM compute_ledger WHERE event_key=?", BigDecimal.class, event)));
            return null;
        }).when(fixture.rewards).onIncomePosted(eq(1L), anyString());
        var paid = fixture.tx.execute(status -> fixture.automatic.activate(1, request));
        assertEquals("90.00000000", paid.get("paidIncome"));
        assertEquals("90.00000000", paid.get("expectedIncome"));
        var snapshot = fixture.data.jsonMap(fixture.jdbc.queryForObject("SELECT rule_snapshot FROM compute_auto_contract", String.class));
        assertEquals("100.00000000", snapshot.get("baseIncome"));
        assertEquals("90.00", snapshot.get("tokenIncomePercent"));
        assertEquals("90.00000000", snapshot.get("settledIncome"));
        fixture.jdbc.update("UPDATE compute_member SET token_income_percent=0 WHERE id=1");
        var replay = fixture.tx.execute(status -> fixture.automatic.activate(1, request));
        assertEquals(paid.get("orderNo"), replay.get("orderNo"));
        assertEquals(paid.get("paidIncome"), replay.get("paidIncome"));
        balance(fixture.jdbc, "COMPUTE", "10000", "0");
        balance(fixture.jdbc, "EARNINGS", "90", "0");
        verify(fixture.rewards, times(1)).onIncomePosted(eq(1L), anyString());
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_auto_contract", Integer.class));
    }

    @Test void zeroPercentStillConsumesExecutionWithoutIncomeOrReferralAndFixedRewardRoundsDown() {
        var fixture = discountFixture();
        fixture.jdbc.update("UPDATE compute_member SET token_income_percent=0 WHERE id=1");
        var zero = fixture.tx.execute(status -> fixture.automatic.activate(1, Map.of("requestId", "zero-token-income")));
        assertEquals("0.00000000", zero.get("paidIncome"));
        assertEquals(999L, fixture.automatic.status(1).get("remainingCount"));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_auto_settlement", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger", Integer.class));
        verify(fixture.rewards, never()).onIncomePosted(anyLong(), anyString());
        fixture.jdbc.update("UPDATE compute_member SET token_income_percent=90.25 WHERE id=1");
        fixture.jdbc.update("UPDATE compute_level SET vip_reward_mode='FIXED',vip_fixed_reward=1.23456789 WHERE id=1");
        var fixed = fixture.tx.execute(status -> fixture.automatic.activate(1, Map.of("requestId", "fixed-token-income")));
        assertEquals("1.11419752", fixed.get("paidIncome"));
        balance(fixture.jdbc, "EARNINGS", "1.11419752", "0");
    }

    @Test void providerModelsAndAllocationPolicyAreFixedBeforeDelayAndCannotPayAgain() {
        var fixture = discountFixture();
        fixture.jdbc.update("INSERT INTO compute_provider VALUES(1,'供应商甲',1)");
        fixture.jdbc.update("INSERT INTO compute_model VALUES(1,1,'模型甲',1),(2,1,'模型乙',1),(3,1,'模型丙',1),(4,1,'模型丁',1),(5,1,'停用模型',0)");
        fixture.jdbc.update("UPDATE compute_level SET auto_provider_ids='[1]' WHERE id=1");
        var request = Map.<String,Object>of("requestId", "fixed-provider-models");
        var paid = fixture.tx.execute(status -> fixture.automatic.activate(1, request));
        long id = ((Number) paid.get("id")).longValue();
        String snapshot = fixture.jdbc.queryForObject("SELECT rule_snapshot FROM compute_auto_contract WHERE id=?", String.class, id);
        fixture.jdbc.update("UPDATE compute_model SET enabled=0 WHERE id<=4");
        fixture.jdbc.update("UPDATE compute_model SET enabled=1 WHERE id=5");
        fixture.tx.execute(status -> fixture.automatic.activate(1, request));
        assertEquals(snapshot, fixture.jdbc.queryForObject("SELECT rule_snapshot FROM compute_auto_contract WHERE id=?", String.class, id));
        LocalDateTime ready = LocalDateTime.ofInstant(Instant.parse(paid.get("resultAvailableAt").toString()), ZoneOffset.UTC);
        fixture.tx.executeWithoutResult(status -> fixture.automatic.finishDetails(id, ready));
        String details = fixture.jdbc.queryForObject("SELECT execution_result FROM compute_auto_contract WHERE id=?", String.class, id);
        fixture.tx.executeWithoutResult(status -> fixture.automatic.finishDetails(id, ready.plusMinutes(1)));
        assertEquals(details, fixture.jdbc.queryForObject("SELECT execution_result FROM compute_auto_contract WHERE id=?", String.class, id));
        var result = fixture.data.jsonMap(details);
        assertEquals(org.token.service.compute.ComputeUsageDetails.TOP_THREE_OTHER, result.get("allocationPolicy"));
        assertEquals(4, ((List<?>) result.get("models")).size());
        assertTrue(((List<?>) result.get("modelAllocations")).stream().noneMatch(raw -> ComputeData.number(((Map<?,?>) raw).get("modelId")) == 5));
        var publicResult = (Map<?,?>) fixture.automatic.record(1, paid.get("orderNo").toString()).get("executionResult");
        assertFalse(publicResult.containsKey("modelAllocations"));
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger", Integer.class));
        balance(fixture.jdbc, "EARNINGS", "100", "0");
    }

    private record DiscountFixture(JdbcTemplate jdbc, FixtureComputeData data, TransactionTemplate tx,
                                   ComputeAutoService automatic, ComputeRewardService rewards) { }

    private static DiscountFixture discountFixture() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:token_discount_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        createSchema(jdbc);
        jdbc.update("INSERT INTO compute_member(id,level,enabled) VALUES(1,1,1)");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value,group_name) VALUES('summaryCurrency','USDT','compute')");
        jdbc.update("INSERT INTO compute_level(id,name,enabled,auto_enabled,auto_display_seconds,auto_daily_limit,auto_cooldown_seconds,auto_click_rate_min,auto_click_rate_max,auto_provider_ids) VALUES(1,'折扣测试',1,1,1,1000,0,1,1,'[]')");
        jdbc.update("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen) VALUES(1,'COMPUTE','USDT',10000,0),(1,'EARNINGS','USDT',0,0)");
        var data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        var rewards = mock(ComputeRewardService.class);
        var currencies = new ComputeCurrencyService(data);
        var business = new ComputeBusinessService(data, currencies, new org.token.util.PasswordUtils(), null, null, rewards);
        return new DiscountFixture(jdbc, data, new TransactionTemplate(new DataSourceTransactionManager(source)),
                new ComputeAutoService(data, business, currencies), rewards);
    }

    @Test void concurrentRetriesPayOnceWithoutFreezingAndDetailsCannotPayAgain() throws Exception {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:auto_race_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        var jdbc=new JdbcTemplate(source);
        createSchema(jdbc);
        jdbc.update("INSERT INTO compute_member(id,level,enabled) VALUES(1,1,1)");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value,group_name) VALUES('summaryCurrency','USDT','compute')");
        jdbc.update("INSERT INTO compute_provider VALUES(1,'测试供应方',1)");
        jdbc.update("INSERT INTO compute_model VALUES(1,1,'测试模型',1)");
        jdbc.update("""
                INSERT INTO compute_level(id,name,enabled,auto_enabled,auto_display_seconds,auto_daily_limit,auto_cooldown_seconds,
                auto_click_rate_min,auto_click_rate_max,auto_provider_ids)
                VALUES(1,'并发测试等级',1,1,1,1,60,1,1,'[1]')
                """);
        jdbc.update("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen) VALUES(1,'COMPUTE','USDT',100,0),(1,'EARNINGS','USDT',0,0)");
        var data=new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules());
        var manager=new DataSourceTransactionManager(source);
        var first=new ComputeAutoService(data,new ComputeBusinessService(data),new ComputeCurrencyService(data));
        var second=new ComputeAutoService(data,new ComputeBusinessService(data),new ComputeCurrencyService(data));
        var request=Map.<String,Object>of("requestId","concurrent-token-submit");
        List<Map<String,Object>> accepted=race(
                ()->new TransactionTemplate(manager).execute(status->first.activate(1,request)),
                ()->new TransactionTemplate(manager).execute(status->second.activate(1,request)));
        assertEquals(accepted.get(0).get("orderNo"),accepted.get(1).get("orderNo"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_auto_contract",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='AUTO_START'",Integer.class));
        balance(jdbc,"COMPUTE","100","0");
        balance(jdbc,"EARNINGS","1","0");
        assertNotNull(jdbc.queryForObject("SELECT auto_next_at FROM compute_member WHERE id=1",LocalDateTime.class));

        long id=((Number)accepted.get(0).get("id")).longValue();
        LocalDateTime ready=LocalDateTime.ofInstant(Instant.parse(accepted.get(0).get("resultAvailableAt").toString()),ZoneOffset.UTC);
        race(()->{
            new TransactionTemplate(manager).executeWithoutResult(status->first.finishDetails(id,ready));return true;
        },()->{
            new TransactionTemplate(manager).executeWithoutResult(status->second.finishDetails(id,ready));return true;
        });
        balance(jdbc,"COMPUTE","100","0");
        balance(jdbc,"EARNINGS","1","0");
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM compute_auto_contract WHERE id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_auto_settlement WHERE contract_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='AUTO_RELEASE'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='AUTO_INCOME'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
        assertEquals(0,new BigDecimal("1").compareTo(jdbc.queryForObject("SELECT paid_income FROM compute_auto_contract WHERE id=?",BigDecimal.class,id)));
    }

    private static <T> List<T> race(Callable<T> first,Callable<T> second) throws Exception {
        var workers=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try {
            var one=workers.submit(()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));return first.call();});
            var two=workers.submit(()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));return second.call();});
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));
        } finally {
            start.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));
        }
    }

    private static void balance(JdbcTemplate jdbc,String wallet,String available,String frozen) {
        var row=jdbc.queryForMap("SELECT available,frozen FROM compute_wallet WHERE member_id=1 AND wallet_type=? AND currency='USDT'",wallet);
        assertEquals(0,new BigDecimal(available).compareTo((BigDecimal)row.get("available")));
        assertEquals(0,new BigDecimal(frozen).compareTo((BigDecimal)row.get("frozen")));
    }

    private static void createSchema(JdbcTemplate jdbc) {
        // 仅建立本链路使用的字段；类型、钱包唯一键和幂等唯一键与当前交付 SQL 保持一致。
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,level BIGINT,enabled INT,vip_expires_at TIMESTAMP,auto_next_at TIMESTAMP,token_income_percent DECIMAL(5,2) NOT NULL DEFAULT 100.00)");
        jdbc.execute("CREATE TABLE system_setting(setting_key VARCHAR(100) PRIMARY KEY,setting_value CLOB,group_name VARCHAR(50),revision BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE compute_provider(id BIGINT PRIMARY KEY,name VARCHAR(100),enabled INT)");
        jdbc.execute("CREATE TABLE compute_model(id BIGINT PRIMARY KEY,provider_id BIGINT,name VARCHAR(100),enabled INT)");
        jdbc.execute("""
                CREATE TABLE compute_level(id BIGINT PRIMARY KEY,name VARCHAR(100),enabled INT,auto_enabled INT,
                auto_display_seconds INT,auto_daily_limit INT,auto_cooldown_seconds INT,
                auto_click_rate_min DECIMAL(14,8),auto_click_rate_max DECIMAL(14,8),auto_provider_ids VARCHAR(1000),vip_reward_mode VARCHAR(8) DEFAULT 'RATE',vip_fixed_reward DECIMAL(26,8) DEFAULT 0,vip_details_delay_minutes INT DEFAULT 60)
                """);
        jdbc.execute("""
                CREATE TABLE compute_wallet(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT,wallet_type VARCHAR(16),currency VARCHAR(16),
                available DECIMAL(28,8),frozen DECIMAL(28,8),updated_at TIMESTAMP,UNIQUE(member_id,wallet_type,currency),CHECK(available>=0),CHECK(frozen>=0))
                """);
        jdbc.execute("""
                CREATE TABLE compute_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(80) UNIQUE,member_id BIGINT,wallet_type VARCHAR(16),
                currency VARCHAR(16),kind VARCHAR(32),amount DECIMAL(28,8),frozen_change DECIMAL(28,8),available_after DECIMAL(28,8),
                frozen_after DECIMAL(28,8),reference_id VARCHAR(80),event_key VARCHAR(160),created_at TIMESTAMP,UNIQUE(member_id,event_key))
                """);
        jdbc.execute("""
                CREATE TABLE compute_auto_contract(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(80) UNIQUE,member_id BIGINT,level_id BIGINT,
                level_name VARCHAR(100),request_id VARCHAR(80),currency VARCHAR(16),amount DECIMAL(26,8),daily_rate DECIMAL(14,8),
                rule_snapshot VARCHAR(100000),expected_income DECIMAL(26,8),paid_income DECIMAL(26,8),
                status VARCHAR(16),starts_at TIMESTAMP,created_at TIMESTAMP,
                result_available_at TIMESTAMP,execution_result VARCHAR(100000),execution_time_zone VARCHAR(80),
                execution_finished_at TIMESTAMP,animation_ends_at TIMESTAMP,details_status VARCHAR(8),UNIQUE(member_id,request_id))
                """);
        jdbc.execute("""
                CREATE TABLE compute_auto_settlement(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(80) UNIQUE,contract_id BIGINT,member_id BIGINT,
                settlement_day INT,settlement_date DATE,income DECIMAL(26,8),principal DECIMAL(26,8),created_at TIMESTAMP,UNIQUE(contract_id,settlement_day))
                """);
        TokenTestSchema.content(jdbc);
    }
}
