package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 奖励测试只在隔离 H2 内调用生产资金服务，覆盖审核、真实链路与并发幂等。 */
@Tag("regression")
class ComputeRewardServiceTest {
    private JdbcTemplate jdbc;private TransactionTemplate tx;private FixtureComputeData data;private ComputeRewardService rewards;private ComputeBusinessService business;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:rewards_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(2);
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run", "compute_member_group");


        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1),('USDC','ERC20',1)");
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','teamSupportedDepth','3'),('compute','summaryCurrency','USDT')");
        jdbc.update("INSERT INTO compute_level(auto_provider_ids,id,name,threshold,enabled) VALUES('[]',1,'基础等级',0,1)");
        TokenTestSchema.apply(jdbc);
        data=new FixtureComputeData(jdbc,new ObjectMapper());rewards=new ComputeRewardService(data,new ComputeMemberRelations(jdbc));
        business=new ComputeBusinessService(data,new ComputeCurrencyService(data),new PasswordUtils(),mock(ComputeSystemMessageService.class),null,rewards);
        member(1,"top",null,false);member(2,"parent",1L,false);member(3,"child",2L,false);member(4,"sibling",2L,false);member(5,"test",2L,true);
    }
    private void member(long id,String username,Long parent,boolean test) {
        jdbc.update("INSERT INTO compute_member(id,username,password_hash,level,enabled,invite_code,inviter_id,is_test,created_at,updated_at) VALUES(?,?,'hash',1,1,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,username,"invite-"+id,parent,test);
        tx.executeWithoutResult(status->business.initializeWallets(id));
    }
    private long deposit(long member,String currency,String amount) {
        long id=data.insert("compute_deposit",fields("member_id",member,"wallet_type","COMPUTE","currency",currency,"network","TRC20","amount",new BigDecimal(amount),"status","PENDING","created_at",now()));
        tx.executeWithoutResult(status->business.reviewDeposit(id,true,"测试审核",1));return id;
    }
    private void configure(List<Map<String,Object>> deposits,List<Map<String,Object>> effective) {
        long revision=number(rewards.settings().get("revision"));tx.executeWithoutResult(status->rewards.save(fields("revision",revision,"depositRules",deposits,"effectiveRules",effective),1));
    }
    private Map<String,Object> depositRule(String participation) {return fields("currency","USDT","enabled",true,"participation",participation,"tiers",List.of(
            fields("threshold","100","selfMode","FIXED","selfValue","5","directMode","PERCENT","directValue","1"),
            fields("threshold","500","selfMode","FIXED","selfValue","50","directMode","PERCENT","directValue","5")));}
    private Map<String,Object> effectiveRule(String basis){return fields("currency","USDT","enabled",true,"basis",basis,"threshold","100","tiers",List.of(fields("count",1,"amount","5"),fields("count",2,"amount","7")));}
    private BigDecimal balance(long member,String currency){return jdbc.queryForObject("SELECT COALESCE(SUM(available),0) FROM compute_wallet WHERE member_id=? AND wallet_type='EARNINGS' AND currency=?",BigDecimal.class,member,currency);}
    private void equalMoney(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    private void vipRules(){jdbc.update("UPDATE compute_level SET reward_rules_json=? WHERE id=1",data.json(List.of(
            fields("depth",1,"depositEnabled",true,"depositRate","2","vipEnabled",true,"vipRate","5","productEnabled",false,"productRate","9"),
            fields("depth",2,"depositEnabled",true,"depositRate","1","vipEnabled",true,"vipRate","2","productEnabled",true,"productRate","3"))));}

    @Test void firstDepositUsesHighestTierAndStacksExplicitVipRebatesOnce() {
        configure(List.of(depositRule("FIRST")),List.of());vipRules();long id=deposit(3,"USDT","500");
        equalMoney("50",balance(3,"USDT"));equalMoney("35",balance(2,"USDT"));equalMoney("5",balance(1,"USDT"));
        tx.executeWithoutResult(status->assertTrue(rewards.onDepositApproved(id)));
        deposit(3,"USDT","500");equalMoney("50",balance(3,"USDT"));equalMoney("45",balance(2,"USDT"));
    }
    @Test void allDepositsUseHighestTierAndStableCurrenciesFollowUnifiedUsdAccounting() {
        configure(List.of(depositRule("ALL")),List.of());deposit(3,"USDT","99");deposit(3,"USDT","100");deposit(3,"USDT","500");long nativeDeposit=deposit(3,"USDC","500");
        equalMoney("105",balance(3,"USDT"));equalMoney("51",balance(2,"USDT"));equalMoney("0",balance(2,"USDC"));
        var nativeOrder=data.one("SELECT * FROM compute_deposit WHERE id=?",nativeDeposit);
        assertEquals("USDC",nativeOrder.get("currency"));equalMoney("500",decimal(nativeOrder.get("amount")));
        assertEquals(2L,number(nativeOrder.get("accountingVersion")));equalMoney("500",decimal(nativeOrder.get("accountingAmount")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE member_id=3 AND currency='USDT' AND amount=500 AND kind='DEPOSIT' AND event_key=?",Integer.class,"DEPOSIT:"+nativeDeposit));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award WHERE source_id=? AND member_id=3 AND source_kind='DEPOSIT_SELF' AND currency='USDT' AND amount=50",Integer.class,nativeDeposit));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award WHERE source_id=? AND member_id=2 AND source_kind='DEPOSIT_DIRECT' AND currency='USDT' AND amount=25",Integer.class,nativeDeposit));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award WHERE currency='USDC'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE member_id=2 AND currency='USDC'",Integer.class));
    }
    @Test void cumulativeCustomerMilestonesAreOneTimeAndCurrentQualificationsIncludeTestMembers() {
        configure(List.of(),List.of(effectiveRule("CUMULATIVE")));
        deposit(3,"USDT","60");deposit(3,"USDC","90");equalMoney("5",balance(2,"USDT"));
        deposit(3,"USDT","40");equalMoney("5",balance(2,"USDT"));
        deposit(5,"USDT","1000");equalMoney("5",balance(2,"USDT"));
        deposit(4,"USDT","100");equalMoney("12",balance(2,"USDT"));
        configure(List.of(),List.of(effectiveRule("CUMULATIVE")));deposit(3,"USDT","100");equalMoney("12",balance(2,"USDT"));
        Map<String,Object> center=rewards.center(2);Map<String,Object> rule=ComputeRewardPolicy.maps(center.get("effectiveRules"),32).get(0);
        assertEquals(3L,number(rule.get("effectiveCount")));assertNull(rule.get("nextTier"));
    }
    @Test void firstCustomerQualificationUsesFirstSuccessfulDepositAcrossCurrencies() {
        configure(List.of(),List.of(effectiveRule("FIRST")));
        deposit(3,"USDT","60");deposit(3,"USDT","100");deposit(4,"USDC","200");deposit(4,"USDT","200");equalMoney("5",balance(2,"USDT"));
    }
    @Test void oldTestFirstDepositWithoutReviewTimeCannotTurnANewDepositIntoFirstQualification() {
        configure(List.of(depositRule("FIRST")),List.of(effectiveRule("FIRST")));
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES(?,?)",ComputeTestMemberParticipation.SETTING,now().atOffset(java.time.ZoneOffset.UTC).toString());
        data.insert("compute_deposit",fields("member_id",5,"wallet_type","COMPUTE","currency","USDT","network","TRC20","amount",new BigDecimal("1"),"status","APPROVED","created_at",now().minusDays(1)));
        long fresh=deposit(5,"USDT","500");
        equalMoney("0",balance(5,"USDT"));equalMoney("0",balance(2,"USDT"));
        tx.executeWithoutResult(status->rewards.onDepositApproved(fresh));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award",Integer.class));
        assertEquals(0L,number(ComputeRewardPolicy.maps(rewards.center(2).get("effectiveRules"),32).get(0).get("effectiveCount")));
    }
    @Test void testSourcesEarnDepositAndIncomeRewardsAfterCutoverWithoutReplayingOlderEvents() {
        configure(List.of(depositRule("ALL")),List.of());vipRules();
        long old=deposit(5,"USDT","100");
        equalMoney("0",balance(5,"USDT"));equalMoney("0",balance(2,"USDT"));
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES(?,?)",ComputeTestMemberParticipation.SETTING,now().atOffset(java.time.ZoneOffset.UTC).toString());
        tx.executeWithoutResult(status->rewards.onDepositApproved(old));
        equalMoney("0",balance(2,"USDT"));
        long fresh=deposit(5,"USDT","100");
        equalMoney("5",balance(5,"USDT"));equalMoney("3",balance(2,"USDT"));equalMoney("1",balance(1,"USDT"));
        tx.executeWithoutResult(status->rewards.onDepositApproved(fresh));
        equalMoney("5",balance(5,"USDT"));equalMoney("3",balance(2,"USDT"));
        tx.executeWithoutResult(status->{rewards.lock();business.changeWallet(5,"EARNINGS","USDT",new BigDecimal("100"),BigDecimal.ZERO,"AUTO_INCOME","TEST:income-after-cutover","one");rewards.onIncomePosted(5,"TEST:income-after-cutover");rewards.onIncomePosted(5,"TEST:income-after-cutover");});
        equalMoney("8",balance(2,"USDT"));equalMoney("3",balance(1,"USDT"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award WHERE member_id=5 AND is_virtual=1",Integer.class),"测试身份只保留审计标记，奖励照常入本人钱包");
    }
    @Test void trueAncestorsAndIndependentIncomeSwitchesCrossTestNodes() {
        configure(List.of(),List.of());vipRules();jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=2");
        tx.executeWithoutResult(status->{rewards.lock();business.changeWallet(3,"EARNINGS","USDT",new BigDecimal("100"),BigDecimal.ZERO,"AUTO_INCOME","VIP:test","one");rewards.onIncomePosted(3,"VIP:test");rewards.onIncomePosted(3,"VIP:test");});
        equalMoney("5",balance(2,"USDT"));equalMoney("2",balance(1,"USDT"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='TEAM_REWARD_TEST'",Integer.class));
        tx.executeWithoutResult(status->{rewards.lock();business.changeWallet(3,"EARNINGS","USDT",new BigDecimal("100"),BigDecimal.ZERO,"ORDER_INCOME","PRODUCT:test","two");rewards.onIncomePosted(3,"PRODUCT:test");});
        equalMoney("5",balance(2,"USDT"));equalMoney("5",balance(1,"USDT"));
        deposit(5,"USDT","1000");equalMoney("5",balance(1,"USDT"));
        jdbc.update("UPDATE system_setting SET setting_value='1' WHERE setting_key='teamSupportedDepth'");
        tx.executeWithoutResult(status->{rewards.lock();business.changeWallet(3,"EARNINGS","USDT",new BigDecimal("100"),BigDecimal.ZERO,"AUTO_INCOME","VIP:capped","three");rewards.onIncomePosted(3,"VIP:capped");});
        equalMoney("5",balance(1,"USDT"));
    }
    @Test void depositsBeforeActivationCannotGainAwardsAfterConfigurationChanges() {
        long id=deposit(3,"USDT","100");equalMoney("0",balance(2,"USDT"));
        configure(List.of(depositRule("ALL")),List.of());
        tx.executeWithoutResult(status->rewards.onDepositApproved(id));
        equalMoney("0",balance(2,"USDT"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award",Integer.class));
        deposit(3,"USDT","100");equalMoney("1",balance(2,"USDT"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award",Integer.class));
    }
    @Test void concurrentCallbacksCannotDuplicateMilestonesOrBalances() throws Exception {
        configure(List.of(depositRule("ALL")),List.of());
        long id=data.insert("compute_deposit",fields("member_id",3,"wallet_type","COMPUTE","currency","USDT","network","TRC20","amount",new BigDecimal("500"),"status","APPROVED","reviewed_at",now(),"created_at",now()));
        tx.executeWithoutResult(status->business.changeWallet(3,"COMPUTE","USDT",new BigDecimal("500"),BigDecimal.ZERO,"DEPOSIT","DEPOSIT:"+id,String.valueOf(id)));
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> results=List.of(executor.submit(()->tx.executeWithoutResult(status->rewards.onDepositApproved(id))),executor.submit(()->tx.executeWithoutResult(status->rewards.onDepositApproved(id))));
            for(Future<?> result:results)result.get(10,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();}
        equalMoney("50",balance(3,"USDT"));equalMoney("25",balance(2,"USDT"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_event",Integer.class));
    }
    @Test void settingsUseRevisionAndVipDepthAndRatesAreValidated() {
        configure(List.of(),List.of());assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->rewards.save(fields("revision",0,"depositRules",List.of(),"effectiveRules",List.of()),1)));
        assertThrows(BusinessException.class,()->ComputeRewardPolicy.normalizeVipRules(List.of(fields("depth",4)),3));
        assertThrows(BusinessException.class,()->ComputeRewardPolicy.normalizeVipRules(List.of(fields("depth",1,"vipRate","101")),3));
        assertThrows(BusinessException.class,()->ComputeRewardPolicy.normalizeVipRules(List.of(fields("depth",1),fields("depth",1)),3));
    }
    @Test void vipDepositRebatesUseCurrentRulesWithoutGlobalTiers() {
        vipRules();tx.executeWithoutResult(status->{rewards.lock();ComputeRewardPolicy.activateDepositEngine(data);});
        deposit(3,"USDT","100");equalMoney("2",balance(2,"USDT"));equalMoney("1",balance(1,"USDT"));
        jdbc.update("UPDATE compute_level SET reward_rules_json='[]' WHERE id=1");
        deposit(3,"USDT","100");equalMoney("2",balance(2,"USDT"));
        assertEquals(true,rewards.settings().get("activated"));
    }
    @Test void expiredVipDoesNotReceiveAndPrincipalCannotBeUsedAsIncomeSource() {
        configure(List.of(),List.of());vipRules();jdbc.update("UPDATE compute_member SET vip_expires_at=? WHERE id=2",now().minusDays(1));
        tx.executeWithoutResult(status->{rewards.lock();business.changeWallet(3,"EARNINGS","USDT",new BigDecimal("100"),BigDecimal.ZERO,"AUTO_INCOME","VIP:expiry","expiry");rewards.onIncomePosted(3,"VIP:expiry");});
        equalMoney("0",balance(2,"USDT"));equalMoney("2",balance(1,"USDT"));
        assertTrue(ComputeRewardPolicy.maps(rewards.center(2).get("vipRules"),200).isEmpty());
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->{rewards.lock();business.changeWallet(3,"EARNINGS","USDT",new BigDecimal("100"),BigDecimal.ZERO,"ORDER_PRINCIPAL","PRINCIPAL:fake","fake");rewards.onIncomePosted(3,"PRINCIPAL:fake");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE event_key='PRINCIPAL:fake'",Integer.class));
        equalMoney("100",balance(3,"USDT"));
    }
}
