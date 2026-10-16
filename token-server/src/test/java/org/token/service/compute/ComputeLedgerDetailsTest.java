package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeLedgerDetailsRepository;
import org.token.service.compute.ComputeAgentScope;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离库验证分类来源、分页计数、资金方向与历史身份保护，不执行实际资金业务。 */
class ComputeLedgerDetailsTest {
    private FixtureComputeData data;private JdbcTemplate jdbc;
    @BeforeEach void setup() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:ledger_details_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);data=new FixtureComputeData(jdbc,new ObjectMapper());
        jdbc.execute("CREATE TABLE compute_ledger(id BIGINT PRIMARY KEY,order_no VARCHAR(48),member_id BIGINT,wallet_type VARCHAR(16),currency VARCHAR(16),kind VARCHAR(40),amount DECIMAL(28,8),frozen_change DECIMAL(28,8),reference_id VARCHAR(80),event_key VARCHAR(160),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_reward_award(id BIGINT PRIMARY KEY,member_id BIGINT,source_member_id BIGINT,source_id BIGINT,source_kind VARCHAR(40),currency VARCHAR(16),amount DECIMAL(28,8),source_amount DECIMAL(28,8),depth INT,rule_snapshot VARCHAR(2000),order_no VARCHAR(48))");
        jdbc.execute("CREATE TABLE compute_activity_participation(id BIGINT PRIMARY KEY,member_id BIGINT,currency VARCHAR(16),amount DECIMAL(28,8),activity_type VARCHAR(24),activity_name VARCHAR(160),prize_name VARCHAR(160),status VARCHAR(30),wheel_round INT,redemption_type VARCHAR(20),order_no VARCHAR(48))");
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,username VARCHAR(100),inviter_id BIGINT)");
        jdbc.execute("CREATE TABLE compute_order(id BIGINT PRIMARY KEY,member_id BIGINT,order_no VARCHAR(48),currency VARCHAR(16),product_name VARCHAR(160),product_type VARCHAR(24))");
        jdbc.execute("CREATE TABLE compute_deposit(id BIGINT PRIMARY KEY,member_id BIGINT,order_no VARCHAR(48),currency VARCHAR(16),amount DECIMAL(28,8),accounting_rate DECIMAL(28,8),accounting_amount DECIMAL(28,8),status VARCHAR(20))");
        jdbc.execute("ALTER TABLE compute_deposit ADD wallet_type VARCHAR(16) DEFAULT 'EARNINGS'");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_version INT DEFAULT 1");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_currency VARCHAR(16)");
        jdbc.execute("CREATE TABLE compute_withdrawal(id BIGINT PRIMARY KEY,member_id BIGINT,order_no VARCHAR(48),currency VARCHAR(16),amount DECIMAL(28,8),status VARCHAR(20),accounting_version INT DEFAULT 1,accounting_currency VARCHAR(16),accounting_amount DECIMAL(28,8))");
        jdbc.execute("CREATE TABLE compute_finance_snapshot(business_type VARCHAR(16),business_id BIGINT,member_id BIGINT,usd_amount DECIMAL(28,8))");
        jdbc.update("INSERT INTO compute_member VALUES(1,'parent',NULL),(2,'child',1),(3,'outside',NULL)");
    }
    private void ledger(long id,String kind,String currency,String amount,String frozen,String reference,String event) {
        jdbc.update("INSERT INTO compute_ledger VALUES(?,?,1,'EARNINGS',?,?,?,?,?,?,CURRENT_TIMESTAMP)",id,"LG-"+id,currency,kind,amount,frozen,reference,event);
    }
    private void reward(long id,String kind) {
        jdbc.update("INSERT INTO compute_reward_award VALUES(?,1,2,100,?,'USDT',3,100,1,?,?)",id,kind,"{\"vipRate\":\"3\",\"rewardBase\":\"100\"}","RW-"+id);
        ledger(id,"TEAM_REWARD","USDT","3","0",String.valueOf(id),"V9_REWARD:"+id);
    }
    private List<Map<String,Object>> rows(Map<String,String> filters) {
        List<Object> args=new ArrayList<>();String where=ComputeLedgerDetailsRepository.filters(filters,"f.",args);
        return data.rows("SELECT * FROM "+ComputeLedgerDetailsRepository.table()+" f WHERE 1=1"+where+" ORDER BY f.id",args.toArray());
    }
    @Test void distinguishesEveryCurrentRewardSourceWithoutChangingOriginalKindsOrAmounts() {
        var expected=Map.of("VIP_INCOME","DOWNLINE_TOKEN_REBATE","PRODUCT_INCOME","DOWNLINE_PROJECT_REBATE","VIP_FIRST_DEPOSIT","DOWNLINE_FIRST_DEPOSIT_REBATE","VIP_DEPOSIT","DOWNLINE_DEPOSIT_REBATE","DEPOSIT_DIRECT","DIRECT_DEPOSIT_TIER_REBATE","DEPOSIT_SELF","SELF_DEPOSIT_TIER_REWARD","EFFECTIVE","EFFECTIVE_INVITE_REWARD");
        long id=1;for(String kind:expected.keySet())reward(id++,kind);
        for(String detail:expected.values()) {
            var result=rows(Map.of("detailType",detail));assertEquals(1,result.size());
            assertEquals("TEAM_REWARD",result.get(0).get("kind"));assertEquals("3.00000000",result.get(0).get("amount"));
        }
    }
    @Test void eventProtocolPreventsIdentifierCollisionsAndMismatchedSourcesAreExplicit() {
        reward(1,"VIP_INCOME");
        ledger(2,"TEAM_REWARD","USDT","3","0","1","OTHER:1");
        ledger(3,"TEAM_REWARD","USDC","3","0","1","V9_REWARD:1");
        ledger(4,"TEAM_REWARD","USDT","4","0","1","V9_REWARD:1");
        ledger(5,"TEAM_REWARD","USDT","3","0","not-numeric","V9_REWARD:1");
        assertEquals(1,rows(Map.of("detailType","DOWNLINE_TOKEN_REBATE")).size());
        assertEquals(2,rows(Map.of("detailType","SOURCE_CONFLICT")).size());
        assertEquals(2,rows(Map.of("detailType","LEGACY_REFERRAL_REWARD")).size());
        var conflicts=rows(Map.of("detailType","SOURCE_CONFLICT"));ComputeLedgerDetails.enrich(data,conflicts,null,1L);
        assertEquals(Map.of(),conflicts.get(0).get("detailFacts"));
    }
    @Test void activitiesUseParticipationSnapshotAndRequirePaidStateWithStablecoinCompatibility() {
        long id=1;for(String kind:List.of("WHEEL","CHEST","RED_PACKET")) {
            jdbc.update("INSERT INTO compute_activity_participation VALUES(?,1,'USDC',3,?,'saved name','saved prize','PAID',2,'AUTO',?)",id,kind,"AP-"+id);
            ledger(id,"ACTIVITY_REWARD","USDT","3","0",String.valueOf(id),"ACTIVITY:"+id);id++;
        }
        assertEquals(1,rows(Map.of("detailType","WHEEL_REWARD")).size());
        assertEquals(1,rows(Map.of("detailType","CHEST_REWARD")).size());
        assertEquals(1,rows(Map.of("detailType","RED_PACKET_REWARD")).size());
        jdbc.update("UPDATE compute_activity_participation SET status='PENDING_REDEMPTION' WHERE id=1");
        jdbc.update("UPDATE compute_activity_participation SET currency='TRX' WHERE id=2");
        assertEquals(2,rows(Map.of("detailType","SOURCE_CONFLICT")).size());
    }
    @Test void filteringRunsBeforePageAndCountInPublicEndpoint() {
        reward(1,"VIP_INCOME");reward(2,"VIP_DEPOSIT");reward(3,"VIP_INCOME");reward(4,"VIP_DEPOSIT");
        var catalog=new ComputeCatalogService(data,mock(PasswordUtils.class),mock(ComputeCurrencyService.class));
        var result=catalog.page("ledger",1L,Map.of("detailType","DOWNLINE_TOKEN_REBATE","size","1","page","2"));
        assertEquals(2L,result.get("total"));
        var records=(List<Map<String,Object>>)result.get("records");assertEquals(1,records.size());assertEquals(1L,records.get(0).get("id"));
        assertEquals("DOWNLINE_TOKEN_REBATE",records.get(0).get("detailType"));
        assertFalse(records.get(0).containsKey("detailRewardId"));
    }
    @Test void sourceUserOutsideCurrentScopeIsHiddenButOriginalRebateFactsRemain() {
        reward(1,"VIP_INCOME");jdbc.update("UPDATE compute_reward_award SET source_member_id=3 WHERE id=1");
        var scope=mock(ComputeAgentScope.class);when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(1L)));
        var result=rows(Map.of());ComputeLedgerDetails.enrich(data,result,scope,null);
        Map<?,?> facts=(Map<?,?>)result.get(0).get("detailFacts");
        assertFalse(facts.containsKey("sourceUsername"));assertFalse(facts.containsKey("sourceOrderNo"));
        assertEquals("100",facts.get("rewardBase"));assertEquals("3",facts.get("rewardValue"));assertEquals(1,facts.get("sourceDepth"));
    }
    @Test void withdrawalPostingIsAnOutflowWhileFreezingAndReleasingAreNotIncome() {
        ledger(1,"WITHDRAW_FREEZE","USDT","-100","100","1","WITHDRAW:1:FREEZE");
        ledger(2,"WITHDRAW_APPROVED","USDT","0","-100","1","WITHDRAW:1:REVIEW");
        ledger(3,"WITHDRAW_RELEASE","USDT","100","-100","2","WITHDRAW:2:REVIEW");
        assertTrue(rows(Map.of("direction","IN")).isEmpty());
        var outgoing=rows(Map.of("direction","OUT"));assertEquals(1,outgoing.size());
        assertEquals("WITHDRAW_APPROVED",outgoing.get(0).get("kind"));
        assertEquals(2,rows(Map.of("direction","FROZEN")).size());
        ComputeLedgerDetails.enrich(data,outgoing,null,1L);
        assertEquals("-100.00000000",outgoing.get(0).get("businessAmount"));assertEquals("0.00000000",outgoing.get(0).get("amount"));
    }
    @Test void paymentOrderNumbersRequireMatchingEventIdentityCurrencyWalletAndAmounts() {
        jdbc.update("INSERT INTO compute_deposit(id,member_id,order_no,currency,amount,status,accounting_version,accounting_currency,accounting_amount) VALUES(10,1,'DP-original','TRX',100,'APPROVED',2,'USD',20)");
        jdbc.update("INSERT INTO compute_withdrawal VALUES(20,1,'WD-original','TRX',100,'APPROVED',2,'USD',20)");
        ledger(1,"DEPOSIT","USDT","20","0","10","DEPOSIT:10");
        ledger(2,"WITHDRAW_FREEZE","USDT","-20","20","20","WITHDRAW:20:FREEZE");
        ledger(3,"WITHDRAW_APPROVED","USDT","0","-20","WD-original","WITHDRAW:20:MCAG_FINAL");
        ledger(4,"DEPOSIT","USDT","20","0","10","OTHER:10");
        ledger(5,"DEPOSIT","USDT","21","0","10","DEPOSIT:10");
        ledger(6,"DEPOSIT","USDT","20","0","10","DEPOSIT:10");
        jdbc.update("UPDATE compute_ledger SET member_id=2 WHERE id=6");
        var result=rows(Map.of());ComputeLedgerDetails.enrich(data,result,null,1L);
        assertEquals("DP-original",result.get(0).get("businessOrderNo"));
        assertEquals("WD-original",result.get(1).get("businessOrderNo"));assertEquals("WD-original",result.get(2).get("businessOrderNo"));
        assertEquals("LG-3",result.get(2).get("ledgerNo"));assertEquals("LG-3",result.get(2).get("orderNo"));
        assertEquals(0,decimal(result.get(2).get("accountingBusinessAmount")).compareTo(new java.math.BigDecimal("-20")));
        assertEquals("UNAVAILABLE",result.get(3).get("businessOrderStatus"));assertNull(result.get(3).get("businessOrderNo"));
        for (int index : List.of(4,5)) {assertEquals("CONFLICT",result.get(index).get("businessOrderStatus"));assertNull(result.get(index).get("businessOrderNo"));}
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }

    @Test void legacyPaymentUsesOnlyStoredUsdSnapshotAndMissingSourceNeverGuesses() {
        jdbc.update("INSERT INTO compute_deposit(id,member_id,order_no,currency,amount,status) VALUES(10,1,'DP-legacy','BTC',2,'APPROVED')");
        jdbc.update("INSERT INTO compute_finance_snapshot VALUES('DEPOSIT',10,1,90000)");
        ledger(1,"DEPOSIT","BTC","2","0","10","DEPOSIT:10");
        ledger(2,"DEPOSIT","BTC","2","0","99","DEPOSIT:99");
        ledger(3,"ADMIN_SYSTEM","USDT","8","0","manual","MANUAL:3");
        var result=rows(Map.of());ComputeLedgerDetails.enrich(data,result,null,1L);
        assertEquals("DP-legacy",result.get(0).get("businessOrderNo"));assertEquals("USD",result.get(0).get("accountingCurrency"));
        assertEquals(0,decimal(result.get(0).get("accountingBusinessAmount")).compareTo(new java.math.BigDecimal("90000")));
        assertNull(result.get(1).get("businessOrderNo"));assertNull(result.get(1).get("accountingBusinessAmount"));
        assertEquals(0,decimal(result.get(2).get("accountingBusinessAmount")).compareTo(new java.math.BigDecimal("8")));
        assertEquals("2.00000000",result.get(0).get("amount"));
    }

    @Test void projectRebateUsesOriginalOrderIdentityAndCategory() {
        reward(1,"PRODUCT_INCOME");
        ledger(100,"ORDER_INCOME","USDT","100","0","9","SETTLE:9:1");
        jdbc.update("UPDATE compute_ledger SET member_id=2 WHERE id=100");
        jdbc.update("INSERT INTO compute_order VALUES(9,2,'PO-original','USDT','Original product','GPU')");
        var result=rows(Map.of("detailType","DOWNLINE_PROJECT_REBATE"));
        var scope=mock(ComputeAgentScope.class);when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(true,List.of()));
        ComputeLedgerDetails.enrich(data,result,scope,null);
        Map<?,?> facts=(Map<?,?>)result.get(0).get("detailFacts");
        assertEquals("PO-original",facts.get("projectOrderNo"));assertEquals("Original product",facts.get("productName"));assertEquals("GPU",facts.get("productType"));
    }
}
