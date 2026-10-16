package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.service.compute.ComputeActivityRules;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离真实事务覆盖单笔资格、区间权重、个人指定及旧数据重复入账边界。 */
@Tag("regression")
class ComputeChestTransactionTest {
    private ComputeWheelTransactionTest f;
    @BeforeEach void setup() throws Exception { f=new ComputeWheelTransactionTest();f.setup(); }
    private List<Map<String,Object>> tiers(){return List.of(Map.of("threshold","100","selfCount",1,"inviterCount",0),Map.of("threshold","500","selfCount",3,"inviterCount",0));}
    private List<Map<String,Object>> ranges(){return List.of(Map.of("id","low-range","minReward","1.00000001","maxReward","100","weight","50"),Map.of("id","mid-range","minReward","100","maxReward","200","weight","40"),Map.of("id","high-range","minReward","200","maxReward","300","weight","10"),Map.of("id","disabled-range","minReward","900","maxReward","999","weight","0"));}
    private Map<String,Object> body(String earning) {
        Map<String,Object> body=f.configuration();body.put("type","CHEST");body.put("prizes",List.of());
        body.put("wheelConfig",new LinkedHashMap<>(Map.of("mode","REF","earningType",earning,"tiers",tiers(),"systemControl",true,"chestRanges",ranges(),"descriptionHtml","<p>宝箱说明</p>")));
        return body;
    }
    private long create(String earning){return ((Number)f.tx.execute(s->f.activity.save(body(earning))).get("id")).longValue();}
    private long available(long member,long id){return ((Number)f.tx.execute(s->f.wheel.memberStatus(member,id)).get("availableCount")).longValue();}
    private Map<String,Object> saved(long id) {Map<String,Object> row=f.data.one("SELECT * FROM compute_activity WHERE id=?",id);row.put("wheelConfig",f.wheel.config(row));row.put("prizes",List.of());return row;}
    private Map<String,Object> specify(String key,long draw,String range,long count) {
        Map<String,Object> request=f.adjustment(key,count);request.put("controlType","INDEPENDENT");request.put("targetDraw",draw);request.put("targetRangeId",range);return request;
    }
    @Test void singleDepositUsesHighestTierPerOrderWithoutInvitationOrRepeat() {
        long id=create("SINGLE_DEPOSIT");f.deposit(2,"500");
        assertEquals(3,available(2,id));assertEquals(0,available(1,id));assertEquals(3,available(2,id));
        f.deposit(2,"100");f.deposit(2,"99.99999999");assertEquals(4,available(2,id));
        f.deposit(2,"500");assertEquals(7,available(2,id));assertEquals(0,available(1,id));
        assertEquals(3,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_attempt_grant WHERE beneficiary_role='SELF'",Integer.class));
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_attempt_grant WHERE beneficiary_role='INVITER'",Integer.class));
    }
    @Test void singleAwardCannotChangeByAddingTierAfterThatOrderWasGranted() {
        long id=create("SINGLE_DEPOSIT");Map<String,Object> row=saved(id);Map<String,Object> cfg=f.wheel.config(row);
        cfg.put("tiers",List.of(tiers().get(0)));row.put("wheelConfig",cfg);Map<String,Object> initial=row;f.tx.execute(s->f.activity.save(initial));
        f.deposit(2,"500");assertEquals(1,available(2,id));
        row=saved(id);cfg=f.wheel.config(row);cfg.put("tiers",tiers());row.put("wheelConfig",cfg);Map<String,Object> updated=row;f.tx.execute(s->f.activity.save(updated));
        assertEquals(1,available(2,id));f.deposit(2,"500");assertEquals(4,available(2,id));
    }
    @Test void cumulativeAwardsEachOwnTierOnceAndNeverGrantsInviter() {
        long id=create("CUMULATIVE_DEPOSIT");f.deposit(2,"100");assertEquals(1,available(2,id));
        f.deposit(2,"400");assertEquals(4,available(2,id));f.deposit(2,"500");assertEquals(4,available(2,id));assertEquals(0,available(1,id));
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_attempt_grant WHERE beneficiary_role='SELF' AND source_deposit_id=0",Integer.class));
    }
    @Test void legacySelfAndInviterCountsRemainWithoutRepeatingOrAddingInviterRewards() {
        long id=create("CUMULATIVE_DEPOSIT");f.deposit(2,"100");assertEquals(1,available(2,id));
        // 模拟114已记的本人和邀请次数；115首次兼容初始化不能把现有SELF再加一次。
        f.jdbc.update("UPDATE compute_wheel_member SET chest_earned_offset=NULL WHERE activity_id=?",id);
        f.tx.execute(s->f.wheel.memberStatus(1,id));
        f.jdbc.update("UPDATE compute_wheel_member SET earned_count=1,available_count=1,issued_count=1,chest_earned_offset=NULL WHERE activity_id=? AND member_id=1",id);
        f.jdbc.update("INSERT INTO compute_activity_attempt_grant(activity_id,round_no,source_member_id,beneficiary_member_id,beneficiary_role,threshold_amount,awarded_count,deposit_amount,created_at) VALUES(?,1,2,1,'INVITER',100,1,100,CURRENT_TIMESTAMP)",id);
        assertEquals(1,available(2,id));assertEquals(1,available(1,id));
        f.deposit(2,"400");assertEquals(4,available(2,id));assertEquals(1,available(1,id));
        f.deposit(1,"100");assertEquals(2,available(1,id));
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_attempt_grant WHERE beneficiary_role='INVITER'",Integer.class));
    }
    @Test void upgradingLegacyFirstToSingleStartsAtSaveAndDoesNotAwardSameFirstAgain() {
        long id=create("CUMULATIVE_DEPOSIT");Map<String,Object> cfg=f.wheel.config(saved(id));cfg.put("earningType","FIRST_DEPOSIT");
        f.jdbc.update("UPDATE compute_activity SET wheel_config=? WHERE id=?",f.data.json(cfg),id);f.deposit(2,"500");assertEquals(3,available(2,id));
        Map<String,Object> row=saved(id);cfg=f.wheel.config(row);cfg.put("earningType","SINGLE_DEPOSIT");row.put("wheelConfig",cfg);f.tx.execute(s->f.activity.save(row));
        assertEquals(3,available(2,id));assertNotNull(f.wheel.config(saved(id)).get("chestQualificationStartedAt"));
        f.jdbc.update("INSERT INTO compute_deposit(member_id,wallet_type,currency,network,amount,status,created_at,reviewed_at) VALUES(2,'COMPUTE','USDT','TRC20',500,'APPROVED',?,?)",f.fixtureTime,java.time.LocalDateTime.now(ZoneOffset.UTC).plusSeconds(3));
        assertEquals(6,available(2,id));
    }
    @Test void exactWeightedBoundariesChooseRangeBeforeSamplingMoney() {
        var normalized=ComputeWheelService.normalizeChestRanges(ranges());
        assertEquals("low-range",ComputeWheelService.chestRangeAt(normalized,BigInteger.ZERO).get("id"));
        assertEquals("low-range",ComputeWheelService.chestRangeAt(normalized,BigInteger.valueOf(49_999_999)).get("id"));
        assertEquals("mid-range",ComputeWheelService.chestRangeAt(normalized,BigInteger.valueOf(50_000_000)).get("id"));
        assertEquals("high-range",ComputeWheelService.chestRangeAt(normalized,BigInteger.valueOf(99_999_999)).get("id"));
        assertThrows(BusinessException.class,()->ComputeWheelService.chestRangeAt(normalized,BigInteger.valueOf(100_000_000)));
        for(int i=0;i<20;i++){Map<String,Object> range=ComputeWheelService.chooseChestRange(normalized);assertNotEquals("disabled-range",range.get("id"));BigDecimal amount=ComputeActivityRules.rangeAmount(new BigDecimal(range.get("minReward").toString()),new BigDecimal(range.get("maxReward").toString()));assertEquals(8,amount.scale());assertTrue(amount.compareTo(new BigDecimal(range.get("minReward").toString()))>=0&&amount.compareTo(new BigDecimal(range.get("maxReward").toString()))<=0);}
        assertEquals(new BigDecimal("999999999999999999.12345678"),ComputeActivityRules.rangeAmount(new BigDecimal("999999999999999999.12345678"),new BigDecimal("999999999999999999.12345678")));
    }
    @Test void independentDrawSelectsStableRangeAndReplayTokenOnce() throws Exception {
        long id=create("SINGLE_DEPOSIT");f.tx.execute(s->f.wheel.adjust(id,2,specify("range-target-once",1,"high-range",1),1));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->f.tx.execute(s->f.activity.participate(2,id,"same-chest-request")));
            var second=pool.submit(()->f.tx.execute(s->f.activity.participate(2,id,"same-chest-request")));
            var a=first.get(10,TimeUnit.SECONDS);var b=second.get(10,TimeUnit.SECONDS);
            assertEquals(a.get("id"),b.get("id"));assertEquals("high-range",a.get("rewardRangeId"));assertEquals("PAID",a.get("status"));
            BigDecimal amount=new BigDecimal(a.get("amount").toString());assertTrue(amount.compareTo(new BigDecimal("200"))>=0&&amount.compareTo(new BigDecimal("300"))<=0);
            assertEquals(0,amount.compareTo(f.jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=2 AND wallet_type='EARNINGS'",BigDecimal.class)));
        }finally{pool.shutdownNow();}
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='ACTIVITY_REWARD'",Integer.class));assertEquals(0,available(2,id));
    }
    @Test void disabledRangeCannotBeSpecifiedAndFailedDrawDoesNotConsumeAttempts() {
        long id=create("SINGLE_DEPOSIT");assertThrows(BusinessException.class,()->f.tx.execute(s->f.wheel.adjust(id,2,specify("disabled-target",1,"disabled-range",1),1)));
        f.tx.execute(s->f.wheel.adjust(id,2,specify("high-target",1,"high-range",1),1));
        Map<String,Object> cfg=f.wheel.config(saved(id));List<Map<String,Object>> list=new ArrayList<>();for(var r:ranges()){Map<String,Object> item=new LinkedHashMap<>(r);if("high-range".equals(item.get("id")))item.put("weight","0");list.add(item);}cfg.put("chestRanges",list);f.jdbc.update("UPDATE compute_activity SET wheel_config=? WHERE id=?",f.data.json(cfg),id);
        assertThrows(BusinessException.class,()->f.tx.execute(s->f.activity.participate(2,id,"invalid-draw-range")));assertEquals(1,available(2,id));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }
    @Test void pendingTargetBlocksRangeDriftButReorderingAndPositiveWeightsAreSafe() {
        long id=create("SINGLE_DEPOSIT");f.tx.execute(s->f.wheel.adjust(id,2,specify("stable-range-target",1,"mid-range",1),1));
        Map<String,Object> row=saved(id);Map<String,Object> cfg=f.wheel.config(row);List<Map<String,Object>> reordered=new ArrayList<>(ranges());Collections.reverse(reordered);cfg.put("chestRanges",reordered);row.put("wheelConfig",cfg);f.tx.execute(s->f.activity.save(row));
        for(String action:List.of("remove","amount","zero")) {
            Map<String,Object> edit=saved(id);Map<String,Object> config=f.wheel.config(edit);List<Map<String,Object>> next=new ArrayList<>();
            for(Map<String,Object> range:ranges()){Map<String,Object> item=new LinkedHashMap<>(range);if("mid-range".equals(item.get("id"))){if("remove".equals(action))continue;if("amount".equals(action))item.put("minReward","150");if("zero".equals(action))item.put("weight","0");}next.add(item);}
            config.put("chestRanges",next);edit.put("wheelConfig",config);assertThrows(BusinessException.class,()->f.tx.execute(s->f.activity.save(edit)));
        }
        assertEquals("mid-range",f.tx.execute(s->f.activity.participate(2,id,"stable-range-draw")).get("rewardRangeId"));
    }
    @Test void adjustmentIdempotencyIncludesRangeAndLegacyBlankHashStaysCompatible() {
        long id=create("SINGLE_DEPOSIT");Map<String,Object> req=specify("range-adjust-idempotent",1,"low-range",1);var first=f.tx.execute(s->f.wheel.adjust(id,2,req,1));assertEquals(first.get("id"),f.tx.execute(s->f.wheel.adjust(id,2,req,1)).get("id"));
        req.put("targetRangeId","mid-range");assertThrows(BusinessException.class,()->f.tx.execute(s->f.wheel.adjust(id,2,req,1)));assertEquals(1,available(2,id));
        var old=f.adjustment("old-adjust-idempotent",1);var before=f.tx.execute(s->f.wheel.adjust(id,1,old,1));old.put("targetRangeId","");assertEquals(before.get("id"),f.tx.execute(s->f.wheel.adjust(id,1,old,1)).get("id"));
    }
    @Test void legacySingleRangeFallbackIsVisibleAndPaysWithinOriginalBounds() {
        long id=create("SINGLE_DEPOSIT");Map<String,Object> cfg=f.wheel.config(saved(id));cfg.remove("chestRanges");f.jdbc.update("UPDATE compute_activity SET wheel_config=?,min_reward=5,max_reward=5 WHERE id=?",f.data.json(cfg),id);
        var choices=(List<Map<String,Object>>)f.wheel.members(id,Map.of()).get("prizes");assertEquals("legacy-range",choices.get(0).get("id"));assertEquals("100",choices.get(0).get("weight"));
        f.deposit(2,"100");var result=f.tx.execute(s->f.activity.participate(2,id,"legacy-chest-pay"));assertEquals("legacy-range",result.get("rewardRangeId"));assertEquals(0,new BigDecimal("5").compareTo(new BigDecimal(result.get("amount").toString())));
    }
    @Test void invalidRangeIdentifiersPrecisionAndWeightsAreRejected() {
        for(List<Map<String,Object>> invalid:List.<List<Map<String,Object>>>of(List.of(Map.of("id","x","minReward","1","maxReward","2","weight","0")),List.of(Map.of("id","x","minReward","1","maxReward","2","weight","0.0000001")),List.of(Map.of("id","x","minReward","2","maxReward","1","weight","1")),List.of(Map.of("id","x","minReward","1","maxReward","2","weight","1"),Map.of("id","x","minReward","2","maxReward","3","weight","1"))))assertThrows(BusinessException.class,()->ComputeWheelService.normalizeChestRanges(invalid));
    }
    @Test void chestOptionsNeverReturnWheelAndRetainSeparateActivityIdentity() {
        long chest=create("SINGLE_DEPOSIT");long wheel=f.create(true);
        var options=(List<Map<String,Object>>)f.activity.adminPage(Map.of("type","CHEST","wheelOptions","true")).get("records");assertEquals(List.of(chest),options.stream().map(row->((Number)row.get("id")).longValue()).toList());
        var wheelOptions=(List<Map<String,Object>>)f.activity.adminPage(Map.of("wheelOptions","true")).get("records");assertEquals(List.of(wheel),wheelOptions.stream().map(row->((Number)row.get("id")).longValue()).toList());
    }
}
