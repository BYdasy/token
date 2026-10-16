package org.token.service.compute;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅使用随机 H2 内存库，检查自动到账、跨周期和指定序号的真实事务行为。 */
@Tag("regression")
class ComputeActivityV32TransactionTest {
    private ComputeWheelTransactionTest f;
    @BeforeEach void setup() throws Exception { f=new ComputeWheelTransactionTest();f.setup(); }

    private Map<String,Object> saved(long id) {
        var row=f.data.one("SELECT * FROM compute_activity WHERE id=?",id);
        row.put("wheelConfig",f.wheel.config(row));
        row.put("prizes",f.data.rows("SELECT * FROM compute_activity_prize WHERE activity_id=? ORDER BY sort_order,id",id));
        return row;
    }
    private long chest(String cycle) {
        var body=f.configuration();body.put("type","CHEST");body.put("prizes",List.of());
        body.put("startsAt",f.fixtureTime.minusDays(40).atOffset(ZoneOffset.UTC).toString());
        body.put("endsAt",f.fixtureTime.plusDays(40).atOffset(ZoneOffset.UTC).toString());
        body.put("wheelConfig",Map.of("mode","REF","earningType","SINGLE_DEPOSIT","systemControl",true,"resetCycle",cycle,
                "tiers",List.of(Map.of("threshold","10","selfCount",3,"inviterCount",0)),
                "chestRanges",List.of(Map.of("id","fixed","minReward","5","maxReward","5","weight",1))));
        return ((Number)f.tx.execute(s->f.activity.save(body)).get("id")).longValue();
    }

    @Test void wheelAutoCreditIsAtomicAndReplayDoesNotPayAgainOrChangeOldPendingOrders() throws Exception {
        long id=f.create(true);f.deposit(2,"60");
        var pending=f.draw(id,"manual-before-change");assertEquals("PENDING_REDEMPTION",pending.get("status"));
        var edit=saved(id);((List<Map<String,Object>>)edit.get("prizes")).get(0).put("autoCredit",true);
        f.tx.execute(s->f.activity.save(edit));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->f.draw(id,"automatic-same-request"));
            var b=pool.submit(()->f.draw(id,"automatic-same-request"));
            var first=a.get(10,TimeUnit.SECONDS);var replay=b.get(10,TimeUnit.SECONDS);
            assertEquals(first.get("id"),replay.get("id"));assertEquals("PAID",first.get("status"));
            assertEquals("AUTO",first.get("redemptionType"));assertNull(first.get("redemptionCode"));
        } finally {pool.shutdownNow();}
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='ACTIVITY_REWARD'",Integer.class));
        assertEquals(0,new BigDecimal("5").compareTo(f.jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS'",BigDecimal.class)));
        assertEquals("PENDING_REDEMPTION",f.draw(id,"manual-before-change").get("status"));
        f.tx.execute(s->f.wheel.redeem(((Number)pending.get("id")).longValue(),"AUTO",1));
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='ACTIVITY_REWARD'",Integer.class));
    }

    @Test void targetThirdMeansTheNextDrawAfterTwoUsesAndPastTargetsAreRejected() {
        long id=f.create(false);
        f.tx.execute(s->f.wheel.adjust(id,1,f.adjustment("ordinal-initial-counts",4),1));
        f.draw(id,"ordinal-first-draw");f.draw(id,"ordinal-second-draw");
        long prize=f.jdbc.queryForObject("SELECT id FROM compute_activity_prize WHERE activity_id=? AND probability>0",Long.class,id);
        var target=f.adjustment("ordinal-target-three",0);target.put("controlType","INDEPENDENT");target.put("targetDraw",3);target.put("targetPrizeId",prize);
        f.tx.execute(s->f.wheel.adjust(id,1,target,1));
        assertEquals(prize,((Number)f.draw(id,"ordinal-third-draw").get("prizeId")).longValue());
        target.put("requestId","ordinal-past-target");
        assertThrows(BusinessException.class,()->f.tx.execute(s->f.wheel.adjust(id,1,target,1)));
        target.put("targetDraw",4);f.tx.execute(s->f.wheel.adjust(id,1,target,1));
        assertEquals(prize,((Number)f.draw(id,"ordinal-fourth-draw").get("prizeId")).longValue());
    }

    @Test void resetClearsCountsAndIndependentSettingsWithoutReplayingDepositsOrOldRequests() {
        long id=chest("DAY");f.deposit(2,"10");
        // 首笔在上一自然日，本轮初次正常发放，随后模拟跨过服务端边界。
        f.jdbc.update("UPDATE compute_deposit SET created_at=?",f.fixtureTime.minusDays(1));
        var cfg=f.wheel.config(saved(id));cfg.put("resetCycleStartedAt",ComputeActivityCycle.start("DAY",f.fixtureTime,ZoneOffset.UTC).atOffset(ZoneOffset.UTC).toString());
        f.jdbc.update("UPDATE compute_activity SET wheel_config=? WHERE id=?",f.data.json(cfg),id);
        var adjustment=f.adjustment("cycle-independent-old",1);adjustment.put("controlType","INDEPENDENT");adjustment.put("targetDraw",2);adjustment.put("targetRangeId","fixed");adjustment.put("roundNo",1);
        var old=f.tx.execute(s->f.wheel.adjust(id,2,adjustment,1));
        var record=f.tx.execute(s->f.activity.participate(2,id,"cycle-paid-old-order"));
        cfg.put("resetCycleStartedAt",ComputeActivityCycle.start("DAY",f.fixtureTime.minusDays(1),ZoneOffset.UTC).atOffset(ZoneOffset.UTC).toString());
        f.jdbc.update("UPDATE compute_activity SET wheel_config=? WHERE id=?",f.data.json(cfg),id);
        var preview=(List<Map<String,Object>>)f.tx.execute(s->f.wheel.members(id,Map.of("memberId","2"))).get("records");
        assertEquals(2L,preview.get(0).get("roundNo"));assertEquals(0L,preview.get(0).get("availableCount"));
        var fresh=f.tx.execute(s->f.wheel.memberStatus(2,id));
        for(String key:List.of("availableCount","usedCount","pendingCount","issuedCount","earnedCount","targetDraw"))assertEquals(0L,((Number)fresh.get(key)).longValue(),key);
        assertEquals("UNIFIED",fresh.get("controlType"));assertNull(fresh.get("targetRangeId"));
        assertEquals(old.get("id"),f.tx.execute(s->f.wheel.adjust(id,2,adjustment,1)).get("id"));
        adjustment.put("requestId","cycle-stale-editor-new-request");
        assertThrows(BusinessException.class,()->f.tx.execute(s->f.wheel.adjust(id,2,adjustment,1)));
        assertEquals(record.get("id"),f.tx.execute(s->f.activity.participate(2,id,"cycle-paid-old-order")).get("id"));
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='ACTIVITY_REWARD'",Integer.class));
        f.deposit(2,"10");assertEquals(3L,((Number)f.tx.execute(s->f.wheel.memberStatus(2,id)).get("availableCount")).longValue());
    }

    @Test void periodsUseSystemNaturalDatesAcrossWeekMonthAndDaylightSaving() {
        assertEquals(LocalDateTime.parse("2026-09-24T16:00:00"),ComputeActivityCycle.start("DAY",LocalDateTime.parse("2026-09-25T02:00:00"),ZoneId.of("Asia/Shanghai")));
        assertEquals(LocalDateTime.parse("2026-09-20T16:00:00"),ComputeActivityCycle.start("WEEK",LocalDateTime.parse("2026-09-25T02:00:00"),ZoneId.of("Asia/Shanghai")));
        assertEquals(LocalDateTime.parse("2026-08-31T16:00:00"),ComputeActivityCycle.start("MONTH",LocalDateTime.parse("2026-09-25T02:00:00"),ZoneId.of("Asia/Shanghai")));
        assertEquals(LocalDateTime.parse("2026-03-08T05:00:00"),ComputeActivityCycle.start("DAY",LocalDateTime.parse("2026-03-08T20:00:00"),ZoneId.of("America/New_York")));
        assertEquals(LocalDateTime.parse("2026-03-09T04:00:00"),ComputeActivityCycle.start("DAY",LocalDateTime.parse("2026-03-09T20:00:00"),ZoneId.of("America/New_York")));
    }

    @Test void concurrentFirstAccessAdvancesCycleOnlyOnceAndRequiresFreshEditorRound() throws Exception {
        long id=chest("DAY");var cfg=f.wheel.config(saved(id));
        cfg.put("resetCycleStartedAt",ComputeActivityCycle.start("DAY",f.fixtureTime.minusDays(1),ZoneOffset.UTC).atOffset(ZoneOffset.UTC).toString());
        f.jdbc.update("UPDATE compute_activity SET wheel_config=? WHERE id=?",f.data.json(cfg),id);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->f.tx.execute(s->f.wheel.memberStatus(1,id)));
            var b=pool.submit(()->f.tx.execute(s->f.wheel.memberStatus(2,id)));
            assertEquals(2L,((Number)a.get(10,TimeUnit.SECONDS).get("roundNo")).longValue());
            assertEquals(2L,((Number)b.get(10,TimeUnit.SECONDS).get("roundNo")).longValue());
        } finally {pool.shutdownNow();}
        assertEquals(2L,((Number)f.wheel.config(saved(id)).get("round")).longValue());
        assertThrows(BusinessException.class,()->f.tx.execute(s->f.wheel.adjust(id,1,f.adjustment("missing-reset-round",1),1)));
    }

    private long inviteWheel() {
        long id=f.create(true);var edit=saved(id);var cfg=f.wheel.config(edit);
        cfg.put("earningType","INVITED_MEMBERS");cfg.put("minimumFirstDeposit","10");
        cfg.put("tiers",List.of(Map.of("threshold","1","selfCount",1,"inviterCount",0),Map.of("threshold","2","selfCount",2,"inviterCount",0)));
        edit.put("wheelConfig",cfg);f.tx.execute(s->f.activity.save(edit));return id;
    }
    private long available(long id) {return ((Number)f.tx.execute(s->f.wheel.memberStatus(1,id)).get("availableCount")).longValue();}

    @Test void invitedMembersRewardEachTierOnceUsingFirstSuccessfulUsdDeposit() throws Exception {
        long id=inviteWheel();f.deposit(1,"100");assertEquals(0,available(id));
        f.deposit(2,"9");f.deposit(2,"100");assertEquals(0,available(id));
        f.deposit(3,"10");assertEquals(1,available(id));
        f.jdbc.update("INSERT INTO compute_member(id,username,password_hash,level,enabled,invite_code,created_at,updated_at,inviter_id) VALUES(4,'wheel4','test',1,1,'invite4',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)");
        f.jdbc.update("INSERT INTO compute_deposit(member_id,wallet_type,currency,network,amount,accounting_version,accounting_amount,status,created_at) VALUES(4,'PROJECT','TRX','TRON',3000,2,10,'APPROVED',?)",f.fixtureTime);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->available(id));var b=pool.submit(()->available(id));
            assertEquals(3L,a.get(10,TimeUnit.SECONDS));assertEquals(3L,b.get(10,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_invite_grant",Integer.class));
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_attempt_grant WHERE beneficiary_role IN('SELF','INVITER')",Integer.class));
        var edit=saved(id);var cfg=f.wheel.config(edit);
        cfg.put("tiers",List.of(Map.of("threshold","1","selfCount",99,"inviterCount",0),Map.of("threshold","2","selfCount",99,"inviterCount",0)));
        edit.put("wheelConfig",cfg);f.tx.execute(s->f.activity.save(edit));assertEquals(3,available(id));
        assertEquals(0,new BigDecimal("10").compareTo(f.jdbc.queryForObject("SELECT minimum_first_deposit FROM compute_activity_invite_grant ORDER BY id LIMIT 1",BigDecimal.class)));
    }

    @Test void testIndirectAndHistoricalFirstDepositsDoNotQualify() {
        long id=inviteWheel();f.jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=2");f.deposit(2,"100");
        f.jdbc.update("UPDATE compute_member SET inviter_id=2 WHERE id=3");f.deposit(3,"100");assertEquals(0,available(id));
        f.jdbc.update("UPDATE compute_member SET inviter_id=1,is_test=0 WHERE id=3");
        f.jdbc.update("UPDATE compute_deposit SET created_at=? WHERE member_id=3",f.fixtureTime.minusDays(1));
        f.deposit(3,"100");assertEquals(0,available(id));
    }
    @Test void newTestInviteesQualifyButOldTestFirstDepositsCannotBecomeNewFirstDeposits() {
        long id=inviteWheel();f.jdbc.update("UPDATE compute_member SET is_test=1 WHERE id IN(2,3)");
        f.deposit(2,"100");f.jdbc.update("UPDATE compute_deposit SET created_at=? WHERE member_id=2",f.fixtureTime.minusSeconds(1));
        f.jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute',?,?)",ComputeTestMemberParticipation.SETTING,f.fixtureTime.atOffset(java.time.ZoneOffset.UTC).toString());
        assertEquals(0,available(id));
        f.deposit(2,"100");assertEquals(0,available(id),"真实首充发生于切换前，后续充值不能伪装首充");
        f.deposit(3,"10");assertEquals(1,available(id));assertEquals(1,available(id));
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_invite_grant",Integer.class));
    }

    @Test void changingToInvitedMembersPreservesPreviousIssuedAndPendingCounts() {
        long id=f.create(true);f.deposit(2,"40");assertEquals(2,available(id));
        var edit=saved(id);var cfg=f.wheel.config(edit);cfg.put("earningType","INVITED_MEMBERS");cfg.put("minimumFirstDeposit","10");
        cfg.put("tiers",List.of(Map.of("threshold","1","selfCount",1,"inviterCount",0)));edit.put("wheelConfig",cfg);
        f.tx.execute(s->f.activity.save(edit));assertEquals(3,available(id));assertEquals(3,available(id));
    }

    @Test void editingAutoCreditBeforeFirstDrawDoesNotInvalidateAnAssignedPrize() {
        long id=f.create(false);long prize=f.jdbc.queryForObject("SELECT id FROM compute_activity_prize WHERE activity_id=? AND probability>0",Long.class,id);
        var target=f.adjustment("assigned-before-settings",1);target.put("controlType","INDEPENDENT");target.put("targetDraw",1);target.put("targetPrizeId",prize);
        f.tx.execute(s->f.wheel.adjust(id,1,target,1));var edit=saved(id);
        ((List<Map<String,Object>>)edit.get("prizes")).get(0).put("autoCredit",true);
        f.tx.execute(s->f.activity.save(edit));var result=f.draw(id,"assigned-after-settings");
        assertEquals(prize,((Number)result.get("prizeId")).longValue());assertEquals("PAID",result.get("status"));
    }
}
