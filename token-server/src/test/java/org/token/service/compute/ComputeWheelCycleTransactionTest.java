package org.token.service.compute;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 转盘周期复用真实隔离事务，检查旧兑奖、资格窗口和跨周期后台修改。 */
@Tag("regression")
class ComputeWheelCycleTransactionTest {
    private ComputeWheelTransactionTest f;
    @BeforeEach void setup() throws Exception { f=new ComputeWheelTransactionTest();f.setup(); }

    private Map<String,Object> saved(long id) {
        var row=f.data.one("SELECT * FROM compute_activity WHERE id=?",id);
        row.put("wheelConfig",f.wheel.config(row));
        row.put("prizes",f.data.rows("SELECT * FROM compute_activity_prize WHERE activity_id=? ORDER BY sort_order,id",id));
        return row;
    }
    private long create(String cycle,String earning) {
        long id=f.create(true);var body=saved(id);var cfg=f.wheel.config(body);
        cfg.put("resetCycle",cycle);cfg.put("earningType",earning);cfg.put("minimumFirstDeposit","10");
        cfg.put("tiers",List.of(Map.of("threshold","INVITED_MEMBERS".equals(earning)?"1.00000000":"10","selfCount",1,"inviterCount","INVITED_MEMBERS".equals(earning)?0:1)));
        body.put("startsAt",f.fixtureTime.minusMonths(2).atOffset(ZoneOffset.UTC).toString());
        body.put("endsAt",f.fixtureTime.plusMonths(2).atOffset(ZoneOffset.UTC).toString());
        body.put("wheelConfig",cfg);f.tx.execute(s->f.activity.save(body));return id;
    }
    private Map<String,Object> status(long id,long member) { return f.tx.execute(s->f.wheel.memberStatus(member,id)); }
    private void ageBaseline(long id,String cycle) {
        var cfg=f.wheel.config(saved(id));
        LocalDateTime start=ComputeActivityCycle.start(cycle,f.fixtureTime,ZoneOffset.UTC);
        cfg.put("resetCycleStartedAt",ComputeActivityCycle.start(cycle,start.minusSeconds(1),ZoneOffset.UTC).atOffset(ZoneOffset.UTC).toString());
        f.jdbc.update("UPDATE compute_activity SET wheel_config=? WHERE id=?",f.data.json(cfg),id);
    }

    @ParameterizedTest
    @CsvSource({"DAY,FIRST_DEPOSIT","WEEK,CUMULATIVE_DEPOSIT","MONTH,INVITED_MEMBERS"})
    void newCycleClearsOnlyCurrentAttemptsAndUsesNewQualification(String cycle,String earning) {
        long id=create(cycle,earning);LocalDateTime begin=ComputeActivityCycle.start(cycle,f.fixtureTime,ZoneOffset.UTC);
        f.deposit(2,"10");f.jdbc.update("UPDATE compute_deposit SET created_at=? WHERE member_id=2",begin.minusSeconds(1));
        assertEquals(1L,((Number)status(id,1).get("availableCount")).longValue());
        long prize=f.jdbc.queryForObject("SELECT id FROM compute_activity_prize WHERE activity_id=? AND probability>0",Long.class,id);
        var adjustment=f.adjustment("old-wheel-cycle-control",2);adjustment.put("roundNo",1);adjustment.put("controlType","INDEPENDENT");adjustment.put("targetDraw",2);adjustment.put("targetPrizeId",prize);
        var oldAdjustment=f.tx.execute(s->f.wheel.adjust(id,1,adjustment,1));var oldPrize=f.draw(id,"old-wheel-cycle-prize");
        assertEquals("PENDING_REDEMPTION",oldPrize.get("status"));
        ageBaseline(id,cycle);
        var fresh=status(id,1);assertEquals(2L,((Number)fresh.get("roundNo")).longValue());
        for(String key:List.of("availableCount","usedCount","earnedCount","issuedCount","pendingCount","targetDraw"))assertEquals(0L,((Number)fresh.get(key)).longValue(),key);
        assertEquals("UNIFIED",fresh.get("controlType"));assertNull(fresh.get("targetPrizeId"));
        assertEquals(1,f.jdbc.queryForObject("SELECT used_stock FROM compute_activity_prize WHERE id=?",Integer.class,prize));
        assertEquals(oldAdjustment.get("id"),f.tx.execute(s->f.wheel.adjust(id,1,adjustment,1)).get("id"));
        adjustment.put("requestId","stale-wheel-cycle-control");assertThrows(BusinessException.class,()->f.tx.execute(s->f.wheel.adjust(id,1,adjustment,1)));
        assertThrows(BusinessException.class,()->f.tx.execute(s->f.wheel.adjust(id,1,f.adjustment("missing-wheel-cycle",1),1)));
        assertEquals(oldPrize.get("id"),f.draw(id,"old-wheel-cycle-prize").get("id"));
        long participation=((Number)oldPrize.get("id")).longValue();
        f.tx.execute(s->f.wheel.redeem(participation,"AUTO",1));f.tx.execute(s->f.wheel.redeem(participation,"AUTO",1));
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='ACTIVITY_REWARD'",Integer.class));
        assertEquals(0,new BigDecimal("5").compareTo(f.jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS'",BigDecimal.class)));
        f.deposit(3,"10");assertEquals(1L,((Number)status(id,1).get("availableCount")).longValue());
        assertEquals(1L,((Number)status(id,1).get("availableCount")).longValue());
    }

    @Test void parallelMembersAdvanceWheelOnlyOnce() throws Exception {
        long id=create("DAY","INVITED_MEMBERS");ageBaseline(id,"DAY");
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->status(id,1));var second=pool.submit(()->status(id,2));
            assertEquals(2L,((Number)first.get(10,TimeUnit.SECONDS).get("roundNo")).longValue());
            assertEquals(2L,((Number)second.get(10,TimeUnit.SECONDS).get("roundNo")).longValue());
        } finally {pool.shutdownNow();}
        assertEquals(2L,((Number)f.wheel.config(saved(id)).get("round")).longValue());
    }

    @Test void enablingChangingAndDisablingCyclePreservesCurrentCountsAndServerBaseline() {
        long id=create("NONE","INVITED_MEMBERS");f.deposit(2,"10");assertEquals(1L,((Number)status(id,1).get("availableCount")).longValue());
        for(String cycle:List.of("DAY","WEEK","MONTH","NONE")) {
            var edit=saved(id);var cfg=f.wheel.config(edit);cfg.put("resetCycle",cycle);cfg.put("resetCycleStartedAt","2000-01-01T00:00:00Z");cfg.put("round",999);
            edit.put("wheelConfig",cfg);f.tx.execute(s->f.activity.save(edit));
            assertEquals(1L,((Number)status(id,1).get("roundNo")).longValue());assertEquals(1L,((Number)status(id,1).get("availableCount")).longValue());
            if(!"NONE".equals(cycle))assertNotEquals("2000-01-01T00:00:00Z",f.wheel.config(saved(id)).get("resetCycleStartedAt"));
        }
    }

    @Test void integerMemberThresholdsRoundTripWithoutAmountZerosAndRejectFractionalOrDuplicateCounts() {
        long id=create("NONE","INVITED_MEMBERS");var edit=saved(id);var cfg=f.wheel.config(edit);
        List<Map<String,Object>> tiers=new ArrayList<>();
        for(int n:List.of(1,2,5,10))tiers.add(Map.of("threshold",n+".00000000","selfCount",n,"inviterCount",0));
        cfg.put("tiers",tiers);edit.put("wheelConfig",cfg);var result=f.tx.execute(s->f.activity.save(edit));
        assertEquals(List.of("1","2","5","10"),((List<Map<String,Object>>)f.wheel.config(result).get("tiers")).stream().map(t->t.get("threshold")).toList());
        for(String bad:List.of("1.50000000","0","1000001")) {
            cfg.put("tiers",List.of(Map.of("threshold",bad,"selfCount",1,"inviterCount",0)));
            assertThrows(BusinessException.class,()->f.tx.execute(s->f.activity.save(edit)));
        }
        cfg.put("tiers",List.of(Map.of("threshold","1","selfCount",1,"inviterCount",0),Map.of("threshold","1.00000000","selfCount",2,"inviterCount",0)));
        assertThrows(BusinessException.class,()->f.tx.execute(s->f.activity.save(edit)));
    }
}
