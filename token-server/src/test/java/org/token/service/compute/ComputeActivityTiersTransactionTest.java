package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeWheelRepository;
import org.token.repository.dao.compute.ComputeWheelMapper;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实隔离H2事务覆盖活动阶梯、首充、旧次数兼容和开箱钱包幂等。 */
@Tag("regression")
class ComputeActivityTiersTransactionTest {
    private ComputeWheelTransactionTest f;
    @BeforeEach void setup() throws Exception {f=new ComputeWheelTransactionTest();f.setup();}
    private List<Map<String,Object>> tiers(){return List.of(Map.of("threshold","100","selfCount",1,"inviterCount",1),Map.of("threshold","500","selfCount",3,"inviterCount",2));}
    private long create(String type,String earning) {
        Map<String,Object> body=f.configuration();body.put("type",type);
        body.put("wheelConfig",Map.of("mode","REF","earningType",earning,"tiers",tiers(),"systemControl",true,"descriptionHtml","<p>活动说明</p>"));
        if("CHEST".equals(type)){body.put("minReward","1.00000001");body.put("maxReward","2.00000001");body.put("prizes",List.of());}
        else {
            List<Map<String,Object>> prizes=new ArrayList<>();
            for(int i=0;i<8;i++)prizes.add(new LinkedHashMap<>(Map.of("name","奖项"+i,"amount","5","probability",i==0?"1":"0","stock",100)));
            body.put("prizes",prizes);
        }
        return ((Number)f.tx.execute(s->f.activity.save(body)).get("id")).longValue();
    }
    private long available(long member,long activity){return ((Number)f.tx.execute(s->f.wheel.memberStatus(member,activity)).get("availableCount")).longValue();}
    @Test void wheelSyncReadsItsDepositSourcesOnceAndSeesTheNextDeposit() { assertSingleSourceRead("WHEEL"); }
    @Test void chestSyncReadsItsDepositSourcesOnceAndSeesTheNextDeposit() { assertSingleSourceRead("CHEST"); }
    private void assertSingleSourceRead(String type) {
        long id=create(type,"CUMULATIVE_DEPOSIT");f.deposit(2,"100");
        var measured=spy(f.data);
        // Spy 包裹真实注解 Mapper，充值候选仍在同一个隔离 H2 事务中执行。
        var sourceMapper=spy(measured.mapper(ComputeWheelMapper.class));
        var wheel=new ComputeWheelService(measured,new ComputeWheelRepository(sourceMapper),f.scope,new ComputeBusinessService(measured),null);
        assertEquals(1L,((Number)f.tx.execute(s->wheel.memberStatus(2,id)).get("availableCount")).longValue());
        if("WHEEL".equals(type))verify(sourceMapper,times(1)).wheelTiers(anyMap());
        else verify(sourceMapper,times(1)).chestTiers(anyMap());
        verify(measured,times(1)).setting(eq(ComputeTestMemberParticipation.SETTING),anyString());
        f.deposit(2,"400");
        assertEquals(4L,((Number)f.tx.execute(s->wheel.memberStatus(2,id)).get("availableCount")).longValue());
        if("WHEEL".equals(type)){verify(sourceMapper,times(2)).wheelTiers(anyMap());verify(sourceMapper,never()).chestTiers(anyMap());}
        else {verify(sourceMapper,times(2)).chestTiers(anyMap());verify(sourceMapper,never()).wheelTiers(anyMap());}
        verify(measured,times(2)).setting(eq(ComputeTestMemberParticipation.SETTING),anyString());
    }
    @Test void cumulativeThresholdsStackOnceForSelfAndDirectInviter() {
        long id=create("WHEEL","CUMULATIVE_DEPOSIT");f.deposit(2,"100");
        assertEquals(1,available(2,id));assertEquals(1,available(1,id));
        f.deposit(2,"400");assertEquals(4,available(2,id));assertEquals(3,available(1,id));
        f.deposit(2,"500");assertEquals(4,available(2,id));assertEquals(3,available(1,id));
        assertEquals(4,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_attempt_grant WHERE beneficiary_role<>'LEGACY'",Integer.class));
    }
    @Test void firstDepositUsesLifetimeFirstAndOnlyHighestQualifyingTier() {
        long id=create("WHEEL","FIRST_DEPOSIT");f.deposit(2,"500");
        assertEquals(3,available(2,id));assertEquals(2,available(1,id));
        f.jdbc.update("INSERT INTO compute_deposit(member_id,wallet_type,currency,network,amount,status,created_at,reviewed_at) VALUES(3,'COMPUTE','TRX','TRON',1,'APPROVED',?,?)",f.fixtureTime.minusDays(3),f.fixtureTime.minusDays(2));
        f.deposit(3,"500");assertEquals(0,available(3,id));assertEquals(2,available(1,id));
    }
    @Test void laterTierEditCannotIssueSecondRewardForSameFirstDeposit() {
        long id=create("WHEEL","FIRST_DEPOSIT");
        var cfg=f.wheel.config(f.data.one("SELECT * FROM compute_activity WHERE id=?",id));
        cfg.put("tiers",List.of(tiers().get(0)));f.jdbc.update("UPDATE compute_activity SET wheel_config=? WHERE id=?",f.data.json(cfg),id);
        f.deposit(2,"500");assertEquals(1,available(2,id));assertEquals(1,available(1,id));
        cfg.put("tiers",tiers());f.jdbc.update("UPDATE compute_activity SET wheel_config=? WHERE id=?",f.data.json(cfg),id);
        assertEquals(1,available(2,id));assertEquals(1,available(1,id));
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_attempt_grant WHERE beneficiary_role<>'LEGACY'",Integer.class));
    }
    @Test void cumulativeCountsOnlyActivityWindowAndSkipsTestDeposits() {
        long id=create("CHEST","CUMULATIVE_DEPOSIT");f.deposit(2,"500");
        f.jdbc.update("UPDATE compute_deposit SET created_at=? WHERE member_id=2",f.fixtureTime.minusDays(1));
        assertEquals(0,available(2,id));assertEquals(0,available(1,id));
        f.jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=3");f.deposit(3,"500");assertEquals(0,available(3,id));assertEquals(0,available(1,id));
    }
    @Test void testMembersUseOnlyNewDepositsForWheelAndChestChancesWithoutDuplicatingGrants() {
        f.jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=2");
        long wheel=create("WHEEL","CUMULATIVE_DEPOSIT"),chest=create("CHEST","CUMULATIVE_DEPOSIT");
        f.deposit(2,"500");
        f.jdbc.update("UPDATE compute_deposit SET created_at=? WHERE member_id=2",f.fixtureTime.minusSeconds(1));
        assertEquals(0,available(2,wheel));assertEquals(0,available(2,chest));
        f.jdbc.update("INSERT INTO system_setting(setting_key,setting_value,group_name,revision,updated_at) VALUES(?,?,'compute',0,CURRENT_TIMESTAMP)",ComputeTestMemberParticipation.SETTING,f.fixtureTime.atOffset(ZoneOffset.UTC).toString());
        assertEquals(0,available(2,wheel));assertEquals(0,available(2,chest));
        f.deposit(2,"100");
        assertEquals(1,available(2,wheel));assertEquals(1,available(1,wheel));
        assertEquals(1,available(2,chest));assertEquals(0,available(1,chest));
        assertEquals(1,available(2,wheel));assertEquals(1,available(2,chest));
        assertEquals(3,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_attempt_grant WHERE beneficiary_role IN('SELF','INVITER')",Integer.class));
        var prize=f.tx.execute(s->f.activity.participate(2,chest,"test-cutover-chest-draw"));
        assertEquals("PAID",prize.get("status"));
        assertEquals(prize.get("id"),f.tx.execute(s->f.activity.participate(2,chest,"test-cutover-chest-draw")).get("id"));
        var summary=(Map<?,?>)((List<?>)f.wheel.statistics(Map.of("activityId",String.valueOf(chest))).get("currencies")).get(0);
        assertEquals(1L,((Number)summary.get("memberCount")).longValue());
        assertEquals(1L,((Number)summary.get("participationCount")).longValue());
        assertEquals(0,new BigDecimal(summary.get("paidAmount").toString()).signum(),"测试账号活动金额不进入汇总");
        assertTrue(f.jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=2 AND wallet_type='EARNINGS'",BigDecimal.class).signum()>0,"个人奖励与余额可读");
    }
    @Test void oldInviterEarnedDoesNotSuppressNewSelfAttemptsOrDuplicateInviterAwards() {
        long id=f.create(true);f.deposit(2,"40");assertEquals(2,available(1,id));
        Map<String,Object> activity=f.data.one("SELECT * FROM compute_activity WHERE id=?",id);
        Map<String,Object> cfg=f.wheel.config(activity);cfg.put("tiers",tiers());activity.put("wheelConfig",cfg);
        activity.put("prizes",f.data.rows("SELECT * FROM compute_activity_prize WHERE activity_id=? ORDER BY sort_order,id",id));
        f.tx.execute(s->f.activity.save(activity));
        f.deposit(1,"100");f.deposit(2,"460");
        assertEquals(4,available(1,id));assertEquals(4,available(1,id));
        var state=f.tx.execute(s->f.wheel.memberStatus(1,id));assertEquals(1L,((Number)state.get("selfEarnedCount")).longValue());
    }
    @Test void changingInviterCannotAwardSameSourceTierTwice() {
        long id=create("WHEEL","CUMULATIVE_DEPOSIT");f.deposit(2,"500");assertEquals(3,available(1,id));
        f.jdbc.update("UPDATE compute_member SET inviter_id=3 WHERE id=2");assertEquals(0,available(3,id));assertEquals(3,available(1,id));
        assertEquals(4,available(2,id));
    }
    @Test void chestRewardTokenOnceOnConcurrentReplayAndIgnoresOldCaps() throws Exception {
        long id=create("CHEST","CUMULATIVE_DEPOSIT");f.deposit(2,"100");
        f.jdbc.update("UPDATE compute_activity SET total_stock=1,total_budget=0,per_user_total_limit=1,per_user_daily_limit=1 WHERE id=?",id);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->f.tx.execute(s->f.activity.participate(2,id,"chest-same-request")));
            var b=pool.submit(()->f.tx.execute(s->f.activity.participate(2,id,"chest-same-request")));
            var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
            assertEquals(first.get("id"),second.get("id"));assertEquals("PAID",first.get("status"));assertNotNull(first.get("orderNo"));
            BigDecimal amount=new BigDecimal(first.get("amount").toString());assertTrue(amount.compareTo(new BigDecimal("1.00000001"))>=0&&amount.compareTo(new BigDecimal("2.00000001"))<=0);
            assertEquals(0,amount.compareTo(f.jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=2 AND wallet_type='EARNINGS'",BigDecimal.class)));
        } finally {pool.shutdownNow();}
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='ACTIVITY_REWARD'",Integer.class));
        assertEquals(0,available(2,id));assertThrows(BusinessException.class,()->f.tx.execute(s->f.activity.participate(2,id,"chest-no-attempts")));
    }
    @Test void wheelDrawIgnoresLegacyDailyTotalBudgetAndActivityStockButNotZeroWeight() {
        long id=create("WHEEL","CUMULATIVE_DEPOSIT");f.deposit(2,"500");
        assertEquals(7,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_prize WHERE activity_id=? AND probability=0",Integer.class,id));
        f.jdbc.update("UPDATE compute_activity SET total_stock=1,total_budget=0,per_user_total_limit=1,per_user_daily_limit=1,min_level=10,min_deposit=9999 WHERE id=?",id);
        for(int i=0;i<4;i++) {
            final int draw=i;var record=f.tx.execute(s->f.activity.participate(2,id,"wheel-new-rule-"+draw));
            assertEquals("奖项0",record.get("prizeName"));assertEquals("PENDING_REDEMPTION",record.get("status"));
        }
        assertEquals(0,available(2,id));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }
}
