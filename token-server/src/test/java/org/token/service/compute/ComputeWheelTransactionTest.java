package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.model.query.MemberScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实隔离事务验证次数、奖池预留、兑奖重放与并发，不连接真实数据库。 */
@Tag("regression")
class ComputeWheelTransactionTest {
    JdbcTemplate jdbc;TransactionTemplate tx;FixtureComputeData data;ComputeActivityService activity;ComputeWheelService wheel;ComputeAgentScope scope;
    LocalDateTime fixtureTime;
    @BeforeEach void setup() throws Exception {
        fixtureTime=LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:wheel_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        TokenTestSchema.create(jdbc, "compute_member","system_setting","compute_member_session","compute_recovery_request","compute_currency","compute_wallet","compute_ledger","compute_request","compute_model","compute_model_price","compute_level","compute_product","compute_order","compute_order_settlement","compute_deposit","compute_fee_rule","compute_transfer_rule","compute_transfer","compute_withdrawal","compute_address","compute_job","compute_job_run","compute_activity","compute_activity_prize","compute_activity_participation","compute_wheel_member","compute_wheel_adjustment");
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1)");TokenTestSchema.apply(jdbc);TokenTestSchema.activities(jdbc);
        for(int id=1;id<=3;id++)jdbc.update("INSERT INTO compute_member(id,username,password_hash,level,enabled,invite_code,created_at,updated_at,inviter_id) VALUES(?,?,'test',1,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",id,"wheel"+id,"wheelInvite"+id,id==1?null:1);
        data=new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules());scope=mock(ComputeAgentScope.class);when(scope.relations()).thenReturn(new ComputeMemberRelations(jdbc));when(scope.queryScope()).thenReturn(new MemberScope(true,List.of()));
        var wallets=new ComputeBusinessService(data);wheel=new ComputeWheelService(data,scope,wallets);activity=new ComputeActivityService(jdbc,data,wallets,scope,null,wheel);
    }
    Map<String,Object> configuration(){return new LinkedHashMap<>(Map.of("name","参考转盘","type","WHEEL","currency","USDT","enabled",true,"startsAt",fixtureTime.minusHours(1).atOffset(ZoneOffset.UTC).toString(),"endsAt",fixtureTime.plusHours(1).atOffset(ZoneOffset.UTC).toString(),"totalStock",20,"totalBudget","100","perUserTotalLimit",20,"perUserDailyLimit",20));}
    long create(boolean automatic) {
        return create(automatic,"CUMULATIVE_DEPOSIT");
    }
    long create(boolean automatic,String earningType) {
        var body=configuration();
        body.put("wheelConfig",Map.of("mode","REF","earningType",earningType,"threshold","20",
                "systemControl",automatic,"descriptionHtml","<p>Earn one draw for each qualifying direct referral deposit of 20 USDT.</p>"));
        List<Map<String,Object>> prizes=new ArrayList<>();
        prizes.add(new LinkedHashMap<>(Map.of("name","奖励","amount","5","stock",20,"minDraw",0,"probability","1")));
        for(int slot=1;slot<8;slot++)prizes.add(new LinkedHashMap<>(Map.of("name","停用奖位"+slot,"amount","0","stock",20,"minDraw",0,"probability","0","isNoPrize",true)));
        body.put("prizes",prizes);
        return tx.execute(s->{
            long id=((Number)activity.save(body).get("id")).longValue();
            var saved=wheel.config(data.one("SELECT * FROM compute_activity WHERE id=?",id));
            // 资格夹具使用已经开始的轮次，成功充值明确位于窗口内部，不依赖墙钟采样与 DATETIME(6) 舍入先后。
            saved.put("roundStartedAt",fixtureTime.minusMinutes(30).atOffset(ZoneOffset.UTC).toString());
            jdbc.update("UPDATE compute_activity SET wheel_config=? WHERE id=?",data.json(saved),id);
            return id;
        });
    }
    void deposit(long member,String amount){jdbc.update("INSERT INTO compute_deposit(member_id,wallet_type,currency,network,amount,status,created_at) VALUES(?,'COMPUTE','USDT','TRC20',?,'APPROVED',?)",member,new BigDecimal(amount),fixtureTime);}
    Map<String,Object> adjustment(String key,long add){return new LinkedHashMap<>(Map.of("requestId",key,"addCount",add,"subtractCount",0,"controlType","UNIFIED","targetDraw",0,"remark","测试发放"));}
    Map<String,Object> draw(long id,String key){return tx.execute(s->activity.participate(1,id,key));}

    @Test void probabilityOnlyPrizesPreserveFractionalAndMaximumWeights() {
        long id=create(false);
        var body=new LinkedHashMap<>(activity.detail(id,null));
        @SuppressWarnings("unchecked") var prizes=(List<Map<String,Object>>)body.get("prizes");
        prizes.get(0).put("probability","0.000001");
        prizes.get(1).put("probability","1000000");
        tx.execute(s->activity.save(body));
        var saved=jdbc.queryForList("SELECT probability FROM compute_activity_prize WHERE activity_id=? ORDER BY sort_order,id",id);
        assertEquals(0,new BigDecimal("0.000001").compareTo((BigDecimal)saved.get(0).get("probability")));
        assertEquals(0,new BigDecimal("1000000").compareTo((BigDecimal)saved.get(1).get("probability")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='compute_activity_prize' AND column_name='weight'",Integer.class));
        prizes.get(0).put("probability","0.0000001");
        assertThrows(BusinessException.class,()->tx.execute(s->activity.save(body)));
        prizes.get(0).remove("probability");
        assertThrows(BusinessException.class,()->tx.execute(s->activity.save(body)));
    }

    @Test void jsonBytesAndUnspecifiedPrizeAreSupportedAndFullPayloadIsIdempotent(){
        assertEquals("REF",wheel.config(Map.of("wheelConfig","{\"mode\":\"REF\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).get("mode"));
        long id=create(false);var request=adjustment("attempt-adjust-01",1);var first=tx.execute(s->wheel.adjust(id,1,request,1));
        assertEquals(first.get("id"),tx.execute(s->wheel.adjust(id,1,request,1)).get("id"));
        for(String key:List.of("controlType","targetDraw","targetPrizeId","remark")){var changed=new LinkedHashMap<>(request);changed.put(key,switch(key){case "controlType"->"INDEPENDENT";case "remark"->"不同说明";default->1;});assertThrows(BusinessException.class,()->tx.execute(s->wheel.adjust(id,1,changed,1)));}
        assertEquals(1,jdbc.queryForObject("SELECT available_count FROM compute_wheel_member WHERE member_id=1",Integer.class));
    }
    @Test void memberPreviewMatchesFirstSyncWithoutWritingAndNeverCountsTestReferrals(){
        long id=create(false);deposit(2,"40");jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=3");deposit(3,"100");
        var preview=(List<Map<String,Object>>)wheel.members(id,Map.of("keyword","wheel1")).get("records");assertEquals(2L,preview.get(0).get("pendingCount"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_wheel_member",Integer.class));
        var synced=tx.execute(s->wheel.memberStatus(1,id));assertEquals(2L,((Number)synced.get("pendingCount")).longValue());
    }
    @Test void firstDepositUsesApprovalOrderAndWindowWithIdOnlyBreakingTies(){
        long id=create(false,"FIRST_DEPOSIT");
        var saved=data.one("SELECT * FROM compute_activity WHERE id=?",id);
        var config=wheel.config(saved);config.put("roundStartedAt",fixtureTime.minusHours(1).atOffset(ZoneOffset.UTC).toString());
        jdbc.update("UPDATE compute_activity SET wheel_config=? WHERE id=?",data.json(config),id);
        LocalDateTime created=fixtureTime.minusHours(2);
        LocalDateTime approved=fixtureTime.minusMinutes(10);
        // 创建较早的大额单审核较晚，首次成功的小额单不达门槛，不应获得首充资格。
        jdbc.update("INSERT INTO compute_deposit(member_id,wallet_type,currency,network,amount,status,created_at,reviewed_at) VALUES(2,'COMPUTE','USDT','TRC20',40,'APPROVED',?,?)",created,approved.plusSeconds(1));
        jdbc.update("INSERT INTO compute_deposit(member_id,wallet_type,currency,network,amount,status,created_at,reviewed_at) VALUES(2,'COMPUTE','USDT','TRC20',10,'APPROVED',?,?)",created.plusMinutes(1),approved);
        // 两单创建时间均在活动窗口外，首次成功审核在窗口内，仍应按审核时间计一次。
        jdbc.update("INSERT INTO compute_deposit(member_id,wallet_type,currency,network,amount,status,created_at,reviewed_at) VALUES(3,'COMPUTE','USDT','TRC20',20,'APPROVED',?,?)",created,approved.plusSeconds(1));
        jdbc.update("INSERT INTO compute_deposit(member_id,wallet_type,currency,network,amount,status,created_at,reviewed_at) VALUES(3,'COMPUTE','USDT','TRC20',20,'APPROVED',?,?)",created.plusMinutes(1),approved);
        var preview=(List<Map<String,Object>>)wheel.members(id,Map.of("keyword","wheel1")).get("records");
        assertEquals(1L,preview.get(0).get("pendingCount"));
        // 同时审核时用较小编号打平，因此该下级的 40 USDT 成为首充。
        jdbc.update("UPDATE compute_deposit SET reviewed_at=? WHERE member_id=2",approved);
        var synced=tx.execute(s->wheel.memberStatus(1,id));
        assertEquals(2L,((Number)synced.get("pendingCount")).longValue());
    }
    @Test void legacyAndRedPacketIgnoreUnusedWheelDescriptionButReferenceRequiresIt(){
        var legacy=configuration();
        legacy.put("wheelConfig",Map.of("mode","LEGACY","earningType","FIRST_DEPOSIT","threshold","20","descriptionHtml",""));
        legacy.put("prizes",List.of(Map.of("name","历史奖励","amount","5","probability",1,"stock",20)));
        assertThrows(BusinessException.class,()->tx.execute(s->activity.save(legacy)));
        var packet=configuration();packet.put("type","RED_PACKET");packet.put("minReward","1");packet.put("maxReward","1");packet.put("totalBudget","20");
        packet.put("wheelConfig",Map.of("mode","REF","descriptionHtml",""));
        assertNotNull(tx.execute(s->activity.save(packet)).get("id"));
        var reference=configuration();reference.put("prizes",legacy.get("prizes"));
        reference.put("wheelConfig",Map.of("mode","REF","earningType","FIRST_DEPOSIT","threshold","20","descriptionHtml",""));
        assertThrows(BusinessException.class,()->tx.execute(s->activity.save(reference)));
    }
    @Test void redemptionCodeIsUniqueWhileLegacyNullCodesRemainCompatible(){
        long id=create(true);deposit(2,"40");var first=draw(id,"unique-code-first");var second=draw(id,"unique-code-second");
        assertNotEquals(first.get("redemptionCode"),second.get("redemptionCode"));
        assertTrue(String.valueOf(first.get("redemptionCode")).matches("(?=.*[A-Z])(?=.*[2-9])[A-Z2-9]{6}"));
        assertEquals(first.get("redemptionCode"),draw(id,"unique-code-first").get("redemptionCode"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx.execute(s->jdbc.update("UPDATE compute_activity_participation SET redemption_code=? WHERE id=?",first.get("redemptionCode"),second.get("id"))));
        jdbc.update("UPDATE compute_activity_participation SET redemption_code=NULL WHERE activity_id=?",id);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_participation WHERE activity_id=? AND redemption_code IS NULL",Integer.class,id));
    }
    @Test void automaticAttemptsAreIssuedOnceAndDrawReservesUntilRedemption(){
        long id=create(true);deposit(2,"40");var first=draw(id,"wheel-draw-first");assertEquals("PENDING_REDEMPTION",first.get("status"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
        assertEquals(first.get("id"),draw(id,"wheel-draw-first").get("id"));
        draw(id,"wheel-draw-second");assertThrows(BusinessException.class,()->draw(id,"wheel-draw-third"));
        assertEquals(2,jdbc.queryForObject("SELECT used_count FROM compute_wheel_member WHERE member_id=1",Integer.class));
        long record=((Number)first.get("id")).longValue();tx.execute(s->wheel.redeem(record,"AUTO",1));tx.execute(s->wheel.redeem(record,"AUTO",1));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='ACTIVITY_REWARD'",Integer.class));assertEquals(0,new BigDecimal("5").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS'",BigDecimal.class)));
    }
    @Test void concurrentRedemptionTokenOnceAndManualDoesNotCredit() throws Exception {
        long id=create(true);deposit(2,"40");long record=((Number)draw(id,"concurrent-draw").get("id")).longValue();
        var pool=Executors.newFixedThreadPool(2);try{var first=pool.submit(()->tx.execute(s->wheel.redeem(record,"AUTO",1)));var second=pool.submit(()->tx.execute(s->wheel.redeem(record,"AUTO",1)));assertEquals("PAID",first.get(10,TimeUnit.SECONDS).get("status"));assertEquals("PAID",second.get(10,TimeUnit.SECONDS).get("status"));}finally{pool.shutdownNow();}
        long manual=((Number)draw(id,"manual-draw").get("id")).longValue();assertEquals("MANUAL_REDEEMED",tx.execute(s->wheel.redeem(manual,"MANUAL",1)).get("status"));assertThrows(BusinessException.class,()->tx.execute(s->wheel.redeem(manual,"AUTO",1)));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }
    @Test void editingFuturePrizeAmountDoesNotChangePendingRedemptionSnapshot(){
        long id=create(true);deposit(2,"40");var first=draw(id,"before-prize-edit");
        assertEquals(2,jdbc.queryForObject("SELECT earned_count FROM compute_wheel_member WHERE activity_id=? AND member_id=1",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT available_count FROM compute_wheel_member WHERE activity_id=? AND member_id=1",Integer.class,id));
        var saved=((List<Map<String,Object>>)activity.adminPage(Map.of()).get("records")).get(0);var prizes=(List<Map<String,Object>>)saved.get("prizes");prizes.get(0).put("amount","7");tx.execute(s->activity.save(saved));
        assertEquals(0,new BigDecimal("7").compareTo(new BigDecimal(draw(id,"after-prize-edit").get("amount").toString())));
        tx.execute(s->wheel.redeem(((Number)first.get("id")).longValue(),"AUTO",1));assertEquals(0,new BigDecimal("5").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS'",BigDecimal.class)));
    }
    @Test void designatedPrizeIgnoresRetiredGlobalBudgetButUnavailableStockRollsBackAttempts(){
        long id=create(false);long prize=jdbc.queryForObject("SELECT id FROM compute_activity_prize WHERE activity_id=? AND probability>0",Long.class,id);
        var input=adjustment("independent-draw",1);input.put("controlType","INDEPENDENT");input.put("targetDraw",1);input.put("targetPrizeId",prize);tx.execute(s->wheel.adjust(id,1,input,1));
        jdbc.update("UPDATE compute_activity SET total_budget=0 WHERE id=?",id);
        jdbc.update("UPDATE compute_activity_prize SET used_stock=stock WHERE id=?",prize);
        assertThrows(BusinessException.class,()->draw(id,"stock-failed"));assertEquals(1,jdbc.queryForObject("SELECT available_count FROM compute_wheel_member WHERE member_id=1",Integer.class));
        jdbc.update("UPDATE compute_activity_prize SET used_stock=0 WHERE id=?",prize);
        assertEquals(prize,((Number)draw(id,"stock-restored").get("prizeId")).longValue());
    }

    @Test void singletonEightSlotsAndNoPrizePreserveAttemptsWithoutCreatingRedemptionOrMoney() {
        long id=create(false);
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM compute_activity_prize WHERE activity_id=?",Integer.class,id));
        assertThrows(BusinessException.class,()->create(false));
        var saved=((List<Map<String,Object>>)activity.adminPage(Map.of()).get("records")).get(0);
        var prizes=(List<Map<String,Object>>)saved.get("prizes");
        prizes.get(0).put("isNoPrize",true);prizes.get(0).put("amount","0");
        tx.execute(s->activity.save(saved));
        tx.execute(s->wheel.adjust(id,1,adjustment("no-prize-grant",1),1));
        var result=draw(id,"no-prize-draw");
        assertEquals("NO_PRIZE",result.get("status"));assertNull(result.get("redemptionCode"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT used_count FROM compute_wheel_member WHERE member_id=1",Integer.class));
        assertEquals(result.get("id"),draw(id,"no-prize-draw").get("id"));
        assertThrows(BusinessException.class,()->tx.execute(s->wheel.redeem(((Number)result.get("id")).longValue(),"AUTO",1)));
        long disabledPrize=jdbc.queryForObject("SELECT MIN(id) FROM compute_activity_prize WHERE activity_id=? AND probability=0",Long.class,id);
        var input=adjustment("disabled-target",1);input.put("controlType","INDEPENDENT");input.put("targetDraw",2);input.put("targetPrizeId",disabledPrize);
        assertThrows(BusinessException.class,()->tx.execute(s->wheel.adjust(id,1,input,1)));
        prizes.remove(7);assertThrows(BusinessException.class,()->tx.execute(s->activity.save(saved)));
    }

    @Test void zeroAmountPhysicalPrizeKeepsNameAndManualRedemptionWithoutCreditingMoney() {
        long id=create(false);
        var saved=((List<Map<String,Object>>)activity.adminPage(Map.of()).get("records")).get(0);
        var prizes=(List<Map<String,Object>>)saved.get("prizes");
        prizes.get(0).put("name","实物礼品");prizes.get(0).put("amount","0");prizes.get(0).put("isNoPrize",false);
        tx.execute(s->activity.save(saved));
        tx.execute(s->wheel.adjust(id,1,adjustment("physical-prize-grant",1),1));
        var result=draw(id,"physical-prize-draw");
        assertEquals("实物礼品",result.get("prizeName"));assertEquals("PENDING_REDEMPTION",result.get("status"));
        assertTrue(String.valueOf(result.get("redemptionCode")).matches("(?=.*[A-Z])(?=.*[2-9])[A-Z2-9]{6}"));
        assertEquals("MANUAL_REDEEMED",tx.execute(s->wheel.redeem(((Number)result.get("id")).longValue(),"MANUAL",1)).get("status"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }

    @Test void wheelTranslationsAndDurationValidateWithoutChangingHistoricalConfiguration() {
        long id=create(false);
        var saved=((List<Map<String,Object>>)activity.adminPage(Map.of()).get("records")).get(0);
        Map<String,Object> cfg=wheel.config(saved);
        cfg.put("spinDurationSeconds",12);
        cfg.put("descriptionTranslations",Map.of("en","<p>Prize details</p>","fr","<p>Description du prix</p>"));
        saved.put("wheelConfig",cfg);
        var result=tx.execute(s->activity.save(saved));
        var normalized=wheel.config(result);
        assertEquals(12L,((Number)normalized.get("spinDurationSeconds")).longValue());
        assertEquals("<p>Prize details</p>",((Map<?,?>)normalized.get("descriptionTranslations")).get("en"));
        assertEquals(saved.get("totalBudget"),result.get("totalBudget"));
        cfg.put("spinDurationSeconds",31);
        assertThrows(BusinessException.class,()->tx.execute(s->activity.save(saved)));
        cfg.put("spinDurationSeconds",6);cfg.put("descriptionTranslations",Map.of("fr","<p>Description</p>"));
        assertThrows(BusinessException.class,()->tx.execute(s->activity.save(saved)));
    }

    @Test void everyShortRedemptionCodeContainsLettersAndDigits() {
        for(int index=0;index<200;index++)assertTrue(ComputeActivityService.randomRedemptionCode().matches("(?=.*[A-Z])(?=.*[2-9])[A-Z2-9]{6}"));
    }

    @Test void prizeEditsKeepHistoricalCountersWhileRetiringGlobalCaps() {
        long id=create(true);deposit(2,"40");draw(id,"pool-reservation");
        var saved=((List<Map<String,Object>>)activity.adminPage(Map.of()).get("records")).get(0);
        ((List<Map<String,Object>>)saved.get("prizes")).get(0).put("amount","7");
        var modern=saved;tx.execute(s->activity.save(modern));
        assertEquals(0,new BigDecimal("5").compareTo(jdbc.queryForObject("SELECT total_budget FROM compute_activity WHERE id=?",BigDecimal.class,id)));
        var cfg=wheel.config(data.one("SELECT * FROM compute_activity WHERE id=?",id));cfg.remove("limitsMode");
        jdbc.update("UPDATE compute_activity SET wheel_config=?,total_budget=150,total_stock=30 WHERE id=?",data.json(cfg),id);
        saved=((List<Map<String,Object>>)activity.adminPage(Map.of()).get("records")).get(0);
        ((List<Map<String,Object>>)saved.get("prizes")).get(0).put("amount","8");
        var legacy=saved;tx.execute(s->activity.save(legacy));
        assertEquals(0,new BigDecimal("150").compareTo(jdbc.queryForObject("SELECT total_budget FROM compute_activity WHERE id=?",BigDecimal.class,id)));
        assertEquals(30,jdbc.queryForObject("SELECT total_stock FROM compute_activity WHERE id=?",Integer.class,id));
    }
}
