package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import org.token.service.compute.ComputeActivityRules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.service.compute.ComputeBusinessService;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.model.query.MemberScope;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅使用随机命名的 H2 内存库；验证真实活动事务与钱包记账，不连接工作区数据库。 */
@Tag("regression")
class ComputeActivityTransactionTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ComputeActivityService service;
    private FixtureComputeData data;
    private ComputeAgentScope scope;
    private ComputeBusinessService wallets;

    @BeforeEach
    void isolatedSchema() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:activity_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        TokenTestSchema.create(jdbc, "compute_member","system_setting","compute_member_session","compute_recovery_request","compute_currency","compute_wallet","compute_ledger","compute_request","compute_model","compute_model_price","compute_level","compute_product","compute_order","compute_order_settlement","compute_deposit","compute_fee_rule","compute_transfer_rule","compute_transfer","compute_withdrawal","compute_address","compute_job","compute_job_run","compute_activity","compute_activity_prize","compute_activity_participation");
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1)");
        for (long id = 1; id <= 10; id++) jdbc.update("INSERT INTO compute_member(id,username,password_hash,level,enabled,invite_code,created_at,updated_at) VALUES(?,?,'test',1,1,?,?,?)",
                id, "member" + id, "invite" + id, LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now(ZoneOffset.UTC));
        TokenTestSchema.apply(jdbc);
        TokenTestSchema.activities(jdbc);
        data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        scope = mock(ComputeAgentScope.class);
        when(scope.relations()).thenReturn(new ComputeMemberRelations(jdbc));
        when(scope.queryScope()).thenReturn(new MemberScope(true,List.of()));
        wallets = new ComputeBusinessService(data);
        service = new ComputeActivityService(jdbc, data, wallets, scope);
    }

    private Map<String, Object> config(String type, long stock, String budget) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "测试活动"); request.put("type", type); request.put("currency", "USDT"); request.put("enabled", true);
        request.put("startsAt", Instant.now().minusSeconds(3600).toString()); request.put("endsAt", Instant.now().plusSeconds(3600).toString());
        request.put("totalStock", stock); request.put("totalBudget", budget); request.put("minReward", "1"); request.put("maxReward", "8");
        request.put("packetMode", "RANDOM"); request.put("perUserTotalLimit", 3); request.put("perUserDailyLimit", 2);
        return request;
    }
    private long create(Map<String, Object> request) { return ((Number) transaction.execute(status -> service.save(request)).get("id")).longValue(); }
    private Map<String, Object> participate(long member, long activity, String request) { return transaction.execute(status -> service.participate(member, activity, request)); }

    @Test
    void concurrentPacketsCannotExceedStockAndDistributeExactBudget() throws Exception {
        long activity = create(config("RED_PACKET", 3, "10"));
        var pool = Executors.newFixedThreadPool(8); CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (long member = 1; member <= 8; member++) {
                long id = member;
                futures.add(pool.submit(() -> { ready.countDown(); start.await(5, TimeUnit.SECONDS);
                    try { participate(id, activity, "packet-" + id); return true; } catch (BusinessException expected) { return false; } }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            int success = 0; for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) success++;
            assertEquals(3, success);
        } finally { pool.shutdownNow(); }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_participation", Integer.class));
        assertEquals(0, new BigDecimal("10").compareTo(jdbc.queryForObject("SELECT SUM(amount) FROM compute_activity_participation", BigDecimal.class)));
        assertEquals(0, new BigDecimal("10").compareTo(jdbc.queryForObject("SELECT SUM(available) FROM compute_wallet WHERE wallet_type='EARNINGS'", BigDecimal.class)));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='ACTIVITY_REWARD'", Integer.class));
        assertEquals(0, new BigDecimal("10").compareTo(jdbc.queryForObject("SELECT used_budget FROM compute_activity WHERE id=?", BigDecimal.class, activity)));
    }

    @Test
    void sameRequestReturnsSameRecordEvenAfterActivityCloses() {
        var config = config("RED_PACKET", 2, "4"); config.put("packetMode", "FIXED"); config.put("minReward", "2"); config.put("maxReward", "2");
        long activity = create(config);
        var first = participate(1, activity, "replay-request");
        jdbc.update("UPDATE compute_activity SET enabled=0 WHERE id=?", activity);
        var second = participate(1, activity, "replay-request");
        assertEquals(first.get("id"), second.get("id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT used_stock FROM compute_activity WHERE id=?", Integer.class, activity));
        long other = create(config("RED_PACKET", 1, "4"));
        assertThrows(BusinessException.class, () -> participate(1, other, "replay-request"));
    }

    @Test
    void walletFailureRollsBackPrizeBudgetAndParticipation() {
        long activity = create(config("RED_PACKET", 1, "5"));
        ComputeBusinessService failing = mock(ComputeBusinessService.class);
        doThrow(new BusinessException("server.compute.saveFailed")).when(failing).creditActivityReward(anyLong(), anyString(), any(), anyLong());
        ComputeActivityService failedService = new ComputeActivityService(jdbc, data, failing, scope);
        assertThrows(BusinessException.class, () -> transaction.execute(status -> failedService.participate(1, activity, "wallet-failure")));
        assertEquals(0, jdbc.queryForObject("SELECT used_stock FROM compute_activity WHERE id=?", Integer.class, activity));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_participation", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger", Integer.class));
    }

    @Test
    void historicalWheelWithoutAttemptRulesRequiresConfigurationWithoutSpending() {
        var config = config("WHEEL", 2, "3");
        List<Map<String,Object>> prizes=new ArrayList<>();
        for(int slot=0;slot<8;slot++)prizes.add(Map.of("name","历史奖位"+slot,"amount","3","stock",1,"probability",slot==0?1:0));
        config.put("prizes",prizes);long activity=create(config);
        BusinessException error=assertThrows(BusinessException.class,()->participate(1,activity,"legacy-needs-rules"));
        assertEquals("server.compute.activity.configurationRequired",error.getMessage());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_participation",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
        assertEquals("CONFIG_REQUIRED",service.detail(activity,1L).get("status"));
    }

    @Test
    void conditionsAndDailyLimitDoNotConsumeAttemptsOnFailure() {
        var config = config("RED_PACKET", 3, "6"); config.put("minLevel", 2); long activity = create(config);
        assertThrows(BusinessException.class, () -> participate(1, activity, "level-failure"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_participation", Integer.class));
        jdbc.update("UPDATE compute_member SET level=2 WHERE id=1");
        participate(1, activity, "daily-first"); participate(1, activity, "daily-second");
        BusinessException limit = assertThrows(BusinessException.class, () -> participate(1, activity, "daily-third"));
        assertEquals("server.compute.activity.dailyLimit", limit.getMessage());
        assertEquals(2, jdbc.queryForObject("SELECT used_stock FROM compute_activity WHERE id=?", Integer.class, activity));
    }

    @Test
    void scopedAdminRecordQueryCannotEscapeMemberFilter() {
        long activity = create(config("RED_PACKET", 2, "4")); participate(1, activity, "scope-one"); participate(2, activity, "scope-two");
        when(scope.queryScope()).thenReturn(new MemberScope(false,List.of(1L)));
        assertEquals(1L, service.participations(null, Map.of()).get("total"));
        assertEquals(0L, service.participations(null, Map.of("memberId", "2")).get("total"));
        doThrow(new BusinessException("server.compute.invalidInput")).when(scope).requireGlobalScope();
        assertThrows(BusinessException.class, () -> service.adminPage(Map.of()));
    }

    @Test
    void dailyCountUsesSystemCalendarAcrossUtcMidnightAndDaylightSaving() {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.resolveSystemTimeZone()).thenReturn("America/New_York");
        ComputeActivityService zoned = new ComputeActivityService(jdbc, data, wallets, scope, settings);
        long activity = create(config("RED_PACKET", 3, "6"));
        var first = participate(1, activity, "timezone-first");
        var second = participate(1, activity, "timezone-second");
        jdbc.update("UPDATE compute_activity_participation SET created_at=? WHERE id=?", LocalDateTime.of(2026, 9, 10, 23, 30), first.get("id"));
        jdbc.update("UPDATE compute_activity_participation SET created_at=? WHERE id=?", LocalDateTime.of(2026, 9, 11, 0, 30), second.get("id"));
        // UTC 已经跨日，纽约仍属同一天，两条记录均占用每日限额；当地午夜后才清零。
        assertEquals(2, zoned.todayCount(activity, 1, LocalDateTime.of(2026, 9, 11, 1, 0)));
        assertEquals(0, zoned.todayCount(activity, 1, LocalDateTime.of(2026, 9, 11, 4, 0)));
        // 春季切换日只有二十三小时，下一自然日的 UTC 04:00 必须排除在当天之外。
        jdbc.update("UPDATE compute_activity_participation SET created_at=? WHERE id=?", LocalDateTime.of(2026, 3, 8, 5, 0), first.get("id"));
        jdbc.update("UPDATE compute_activity_participation SET created_at=? WHERE id=?", LocalDateTime.of(2026, 3, 9, 4, 0), second.get("id"));
        assertEquals(1, zoned.todayCount(activity, 1, LocalDateTime.of(2026, 3, 8, 12, 0)));
        verify(settings, times(3)).resolveSystemTimeZone();
    }

    @Test
    void administrativeFiltersKeepMatchingCountAndRecords() {
        var config = config("RED_PACKET", 2, "4"); long activity = create(config);
        participate(1, activity, "filtered-record");
        assertEquals(1L, service.adminPage(Map.of("status", "ENABLED", "keyword", "测试", "startTime", Instant.now().minusSeconds(60).toString())).get("total"));
        assertEquals(0L, service.adminPage(Map.of("status", "DISABLED")).get("total"));
        when(scope.queryScope()).thenReturn(new MemberScope(true,List.of()));
        assertEquals(1L, service.participations(null, Map.of("keyword", "member1", "startTime", Instant.now().minusSeconds(60).toString())).get("total"));
        assertEquals(0L, service.participations(null, Map.of("keyword", "member2")).get("total"));
        assertEquals(0L, service.participations(1L, Map.of("endTime", Instant.now().minusSeconds(60).toString())).get("total"));
        assertEquals("server.compute.activity.invalidInput",assertThrows(BusinessException.class,
                ()->service.adminPage(Map.of("status","unknown"))).getMessage());
        assertEquals("server.compute.activity.invalidTime",assertThrows(BusinessException.class,
                ()->service.participations(null,Map.of("startTime","invalid"))).getMessage());
        assertEquals("server.compute.activity.invalidInput",assertThrows(BusinessException.class,
                ()->service.participations(null,Map.of("size","0"))).getMessage());
    }

    @Test
    void integerAllocationRejectsInvalidBoundsAndKeepsSmallestUnits() {
        assertThrows(BusinessException.class, () -> ComputeActivityRules.nonnegative("0.000000001"));
        assertThrows(BusinessException.class, () -> ComputeActivityRules.packetAmount(new BigDecimal("1"), 2, BigDecimal.ONE, BigDecimal.TEN));
        assertEquals(new BigDecimal("0.00000001"), ComputeActivityRules.packetAmount(new BigDecimal("0.00000001"), 1, new BigDecimal("0.00000001"), BigDecimal.ONE));
    }
}
