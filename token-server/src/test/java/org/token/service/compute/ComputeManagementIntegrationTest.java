package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.compute.ComputeData;
import org.token.model.request.compute.ComputeMemberCreateRequest;
import org.token.model.request.compute.ComputeAuthRequests;
import org.token.service.compute.ComputeAgentScope;
import org.token.exception.BusinessException;
import org.token.model.security.JwtUserPrincipal;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.token.repository.compute.ComputeData.*;

/** 真实邀请链、范围、资金与迁移均运行生产服务，数据库只用本机内存。 */
@Tag("regression")
class ComputeManagementIntegrationTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private FixtureComputeData data;
    private ComputeAgentScope scope;
    private ComputeBusinessService business;
    private ComputeManagementService service;
    private ComputeAccountService accounts;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:v4_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(2);
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run", "compute_member_group", "compute_activity", "compute_activity_prize", "compute_activity_participation");





        jdbc.execute("CREATE TABLE ad_master(uid INT PRIMARY KEY,username VARCHAR(40),state INT)");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('system.time-zone','Asia/Shanghai')");
        jdbc.update("INSERT INTO ad_master VALUES(1,'admin',1),(2,'bound',1),(3,'global',1),(4,'second',1)");
        jdbc.execute("ALTER TABLE ad_master ADD role INT DEFAULT 2");
        jdbc.execute("CREATE TABLE user_role(id INT PRIMARY KEY,isSelf INT)");
        jdbc.update("INSERT INTO user_role VALUES(1,1),(2,0)");
        jdbc.update("UPDATE ad_master SET role=1 WHERE uid=1");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('summaryCurrency','USDT'),('registrationReward','0'),('depositRewardRate','0'),('teamDepth','3')");
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1),('USDC','ERC20',1)");
        jdbc.update("INSERT INTO compute_level(auto_provider_ids,id,name,threshold,enabled) VALUES('[]',1,'基础',0,1)");
        TokenTestSchema.apply(jdbc);
        ComputeMcagTestSchema.apply(jdbc);
        data=new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules());scope=new ComputeAgentScope(jdbc);business=new ComputeBusinessService(data);
        service=new ComputeManagementService(data,jdbc,scope,new ComputeCatalogService(data,new PasswordUtils()),business);
        accounts=new ComputeAccountService(jdbc,new PasswordUtils(),business,scope);
        login(1,"super");
        add(1,"alpha",null);add(2,"child",1L);add(3,"bravo",null);add(4,"grandchild",2L);
        tx.executeWithoutResult(status->service.saveBinding(Map.of("adminUid",2,"memberIds",List.of(1))));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void add(long id,String name,Long inviter){
        jdbc.update("INSERT INTO compute_member(id,username,email,phone,password_hash,level,enabled,invite_code,inviter_id,created_at,updated_at) VALUES(?,?,?,?,?,1,1,?,?,?,?)",id,name,name+"@example.invalid","+100000"+id,new PasswordUtils().hashPassword("123456"),"invite-"+id,inviter,now(),now());
        tx.executeWithoutResult(status->business.initializeWallets(id));
    }
    private void login(int id,String...codes){
        JwtUserPrincipal principal=JwtUserPrincipal.builder().uid(id).username("operator").state(1).authorities(Set.of(codes)).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,Arrays.stream(codes).map(SimpleGrantedAuthority::new).toList()));
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> page){return (List<Map<String,Object>>)page.get("records");}
    private List<Long> ids(Map<String,Object> page){return rows(page).stream().map(row->number(row.get("id"))).toList();}
    @Test void usernameMatchingIsSharedByNormalAndExportPagination() {
        assertEquals(List.of(4L,2L),ids(service.members(Map.of("username","child"))));
        assertEquals(List.of(2L),ids(service.members(Map.of("username","child","usernameMatchMode","exact"))));
        assertTrue(ids(service.members(Map.of("username","chil","usernameMatchMode","exact"))).isEmpty());
        var query=new HashMap<>(Map.of("username","child","usernameMatchMode","contains","size","1","page","1"));
        assertEquals(List.of(4L),ids(service.members(query)));
        query.put("page","2");assertEquals(List.of(2L),ids(service.members(query)));
        login(2,"20100");
        assertTrue(ids(service.members(Map.of("username","bravo","usernameMatchMode","exact"))).isEmpty());
        assertThrows(BusinessException.class,()->service.members(Map.of("usernameMatchMode","invalid")));
    }

    @Test void memberAccessFiltersComposeAndSharePaginationAndExportScope() {
        jdbc.update("UPDATE compute_member SET registration_country='CN',registration_ip='203.0.113.8',last_login_country='US',last_login_ip='2001:db8:abcd::8' WHERE id IN (2,3,4)");
        jdbc.update("UPDATE compute_member SET registration_country='US',registration_ip='198.51.100.1',last_login_country='CN',last_login_ip='192.0.2.1' WHERE id=1");
        login(2,"20100");
        var query=new HashMap<>(Map.of("registrationCountry"," cn ","registrationIp","203.0.113.","lastLoginCountry","us","lastLoginIp","db8:abcd","username","child","size","1","page","1"));
        var first=service.members(query);
        assertEquals(2,number(first.get("total")));assertEquals(List.of(4L),ids(first));
        query.put("page","2");assertEquals(List.of(2L),ids(service.members(query)));
        query.put("size","100");query.put("page","1");assertEquals(List.of(4L,2L),ids(service.members(query)));
        query.put("lastLoginCountry","CN");assertTrue(ids(service.members(query)).isEmpty());
        assertEquals(List.of(1L),ids(service.members(Map.of("registrationCountry","US"))));
        assertEquals(List.of(1L),ids(service.members(Map.of("lastLoginIp","192.0.2"))));
        assertEquals(List.of(4L,2L,1L),ids(service.members(Map.of("registrationCountry"," ","registrationIp"," ","lastLoginCountry"," ","lastLoginIp"," "))));
    }

    @Test void memberIpFiltersTreatWildcardsAsLiteralAndRejectOversizedOrInvalidCountry() {
        jdbc.update("UPDATE compute_member SET registration_ip='2001:db8::1',last_login_ip='203.0.113.9' WHERE id=2");
        for(String key:List.of("registrationIp","lastLoginIp")) {
            for(String literal:List.of("%","_","!","' OR 1=1 --"))assertTrue(ids(service.members(Map.of(key,literal))).isEmpty());
            assertThrows(BusinessException.class,()->service.members(Map.of(key,"a".repeat(65))));
        }
        jdbc.update("UPDATE compute_member SET registration_ip='literal%_!value' WHERE id=2");
        assertEquals(List.of(2L),ids(service.members(Map.of("registrationIp","%_!"))));
        for(String key:List.of("registrationCountry","lastLoginCountry"))
            for(String value:List.of("USA","U%","中国","ſſ","US' OR 1=1"))assertThrows(BusinessException.class,()->service.members(Map.of(key,value)));
    }

    @Test void lastLoginFilterUsesLatestSuccessfulLoginAndKeepsRegistrationSnapshot() {
        jdbc.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeWorkflowIntegrationTest.utcTimestamp'");
        jdbc.update("UPDATE compute_member SET registration_country='CN',registration_ip='203.0.113.8',last_login_country='US',last_login_ip='198.51.100.9' WHERE id=2");
        var application=new ComputeAccountApplicationService(accounts);
        tx.executeWithoutResult(status->application.loginUsername(new ComputeAuthRequests.Login("child","123456"),"127.0.0.1"));
        assertEquals(List.of(2L),ids(service.members(Map.of("lastLoginIp","127.0.0.1"))));
        assertTrue(ids(service.members(Map.of("lastLoginIp","198.51.100.9"))).isEmpty());
        assertTrue(ids(service.members(Map.of("lastLoginCountry","US"))).isEmpty());
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->application.loginUsername(new ComputeAuthRequests.Login("child","incorrect"),"127.0.0.2")));
        assertEquals(List.of(2L),ids(service.members(Map.of("lastLoginIp","127.0.0.1","registrationCountry","CN","registrationIp","203.0.113.8"))));
        assertTrue(ids(service.members(Map.of("lastLoginIp","127.0.0.2"))).isEmpty());
    }

    @Test void memberDirectCountIncludesTestAndDisabledChildrenButNotGrandchildren() {
        add(5,"test-direct",1L);add(6,"outside-direct",3L);
        jdbc.update("UPDATE compute_member SET is_test=1,enabled=0 WHERE id=5");
        login(2,"20100");
        var first=service.members(Map.of("memberId","1"));
        assertEquals(2,number(rows(first).get(0).get("directCount")));
        assertEquals(1,number(rows(service.members(Map.of("memberId","2"))).get(0).get("directCount")));
        assertEquals(0,number(rows(service.members(Map.of("memberId","4"))).get(0).get("directCount")));
        assertTrue(rows(service.members(Map.of("memberId","3"))).isEmpty());
        jdbc.update("UPDATE compute_member SET inviter_id=3 WHERE id=5");
        assertEquals(1,number(rows(service.members(Map.of("memberId","1"))).get(0).get("directCount")));
    }

    @Test void memberDictionariesProvideCompleteCountryOptionsWithoutPrivateFeatureSettings() {
        login(2,"20100");
        var result=service.dictionaries();
        @SuppressWarnings("unchecked") var countries=(List<Map<String,Object>>)result.get("countries");
        var codes=countries.stream().map(row->text(row.get("code"))).toList();
        assertTrue(codes.containsAll(List.of("CN","US","AQ","BV","HM","UM","AC","TA","XK")));
        assertTrue(codes.containsAll(Arrays.asList(Locale.getISOCountries())));
        assertEquals(codes.size(),new HashSet<>(codes).size());
        var china=countries.stream().filter(row->"CN".equals(row.get("code"))).findFirst().orElseThrow();
        assertEquals("中国",china.get("name"));assertEquals("China",china.get("englishName"));
        assertFalse(result.containsKey("phoneDefaultCountry"));assertFalse(result.containsKey("supportContacts"));
        assertThrows(UnsupportedOperationException.class,()->countries.add(Map.of()));
        assertThrows(UnsupportedOperationException.class,()->china.put("name","changed"));
        login(2,"20101");assertEquals(countries,service.dictionaries().get("countries"));
        login(2,"30200");assertFalse(service.dictionaries().containsKey("countries"));
    }
    @Test void addressDeletionSwitchIsLiveAndDoesNotChangeOwnershipOrPendingProtection() {
        long address=data.insert("compute_address",fields("member_id",2,"label","地址","currency","USDT","network","TRC20","address","T"+"1".repeat(33),"created_at",now()));
        assertEquals(true,business.addressPolicy().get("withdrawalAddressDeleteEnabled"));
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('withdrawalAddressDeleteEnabled','false')");
        assertEquals(false,business.addressPolicy().get("withdrawalAddressDeleteEnabled"));
        assertEquals("server.compute.withdrawalAddressDeleteDisabled",assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->business.deleteAddress(2,address))).getMessage());
        assertEquals(0,jdbc.queryForObject("SELECT removed FROM compute_address WHERE id=?",Integer.class,address));
        jdbc.update("UPDATE system_setting SET setting_value='true' WHERE setting_key='withdrawalAddressDeleteEnabled'");
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->business.deleteAddress(3,address)));
        long pending=data.insert("compute_withdrawal",fields("member_id",2,"currency","USDT","network","TRC20","amount",1,"fee",0,"net_amount",1,"address","T"+"1".repeat(33),"rule_snapshot","{}","status","PENDING","created_at",now()));
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->business.deleteAddress(2,address)));
        jdbc.update("UPDATE compute_withdrawal SET status='REJECTED' WHERE id=?",pending);
        tx.executeWithoutResult(s->business.deleteAddress(2,address));
        assertEquals(1,jdbc.queryForObject("SELECT removed FROM compute_address WHERE id=?",Integer.class,address));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_withdrawal WHERE id=?",Integer.class,pending));
    }
    @Test void withdrawalRemoteIdAndRecipientFiltersRemainExactAndRespectMemberScope() throws Exception {
        TokenTestSchema.create(jdbc,"compute_mcag_payout");
        var config=new org.token.config.compute.ComputeMcagProperties();config.setEnabled(true);config.setMerchantId("mch_token");config.setEnvironment("LIVE");
        String address="0x"+"a".repeat(40);
        var snapshot=fields("mode","MCAG","merchantId","mch_token","environment","LIVE","assetId","USDC_POLYGON","networkId","POLYGON_MAINNET");
        long visible=0;
        for(long member:List.of(2L,3L)) {
            long id=data.insert("compute_withdrawal",fields("member_id",member,"currency","USDC","network","POLYGON","amount","10","fee","0","net_amount","10",
                    "address",address,"gateway_snapshot",data.json(snapshot),"rule_snapshot","{}","status","PROCESSING","completion_mode","MCAG_PENDING","created_at",now()));
            ComputeMcagPayoutService.enqueue(data,config,data.one("SELECT * FROM compute_withdrawal WHERE id=?",id));
            jdbc.update("UPDATE compute_mcag_payout SET payout_id=? WHERE withdrawal_id=?","payout_"+member,id);
            if(member==2)visible=id;
        }
        login(2,"30200");
        assertEquals(List.of(visible),ids(service.finances("withdrawals",Map.of("gatewayReference","payout_2"))));
        assertTrue(ids(service.finances("withdrawals",Map.of("gatewayReference","payout_3"))).isEmpty());
        assertTrue(ids(service.finances("withdrawals",Map.of("gatewayReference","payout_"))).isEmpty());
        assertEquals(List.of(visible),ids(service.finances("withdrawals",Map.of("receiverAddress",address.toUpperCase(Locale.ROOT),"currency","USDC","network","POLYGON"))));
        assertTrue(ids(service.finances("withdrawals",Map.of("receiverAddress",address.substring(0,41)))).isEmpty());
        assertThrows(BusinessException.class,()->service.finances("deposits",Map.of("gatewayReference","payout_2")));
        assertThrows(BusinessException.class,()->service.finances("ledger",Map.of("receiverAddress",address)));
    }
    @Test void multipleRootsAndMultipleAdminsUseRealInvitationTree(){
        tx.executeWithoutResult(status->service.saveBinding(Map.of("adminUid",4,"memberIds",List.of(1))));
        login(2,"20100");assertEquals(List.of(4L,2L,1L),ids(service.members(Map.of())));
        assertThrows(BusinessException.class,()->service.saveBinding(Map.of("adminUid",2,"memberIds",List.of())));
        assertThrows(BusinessException.class,()->service.updateMember(3,Map.of("enabled",false)));
        login(4,"20100");assertEquals(List.of(4L,2L,1L),ids(service.members(Map.of())));
        login(1,"super");tx.executeWithoutResult(status->service.saveBinding(Map.of("adminUid",2,"memberIds",List.of(1,3))));
        login(2,"20100");assertEquals(4,ids(service.members(Map.of())).size());
        jdbc.update("UPDATE compute_member SET enabled=0 WHERE id IN(1,3)");assertTrue(ids(service.members(Map.of())).isEmpty());
    }
    @Test void configuredScopeWithoutRootsFailsClosedUntilExplicitlyCleared(){
        jdbc.update("UPDATE ad_master SET scope_configured_at=? WHERE uid=3",now());
        login(3,"20100");assertFalse(scope.isGlobalScope());assertTrue(ids(service.members(Map.of())).isEmpty());
        login(1,"super");tx.executeWithoutResult(status->service.saveBinding(Map.of("adminUid",3,"memberIds",List.of())));
        login(3,"20100");assertTrue(scope.isGlobalScope());
    }
    @Test void creationIgnoresPublicRulesInitializesNoMoneyOrSessionAndDefaultsUngrouped(){
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('usernameEnabled','false'),('emailRequired','true'),('inviteCodeRequired','true'),('emailEnabled','false')");
        var created=tx.execute(status->accounts.createFromAdmin(new ComputeMemberCreateRequest("newbie",null,null,"1234567",null,true)));
        assertNotNull(created);assertEquals(0,created.depth());assertNull(created.groupId());assertNull(created.inviterId());
        assertTrue(new PasswordUtils().matchesPassword("1234567",jdbc.queryForObject("SELECT password_hash FROM compute_member WHERE id=?",String.class,created.id())));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session WHERE member_id=?",Integer.class,created.id()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE member_id=?",Integer.class,created.id()));
        login(2,"20101");assertThrows(BusinessException.class,()->tx.execute(status->accounts.createFromAdmin(new ComputeMemberCreateRequest("outside",null,null,"1234567",null,true))));
        var child=tx.execute(status->accounts.createFromAdmin(new ComputeMemberCreateRequest("inside",null,null,"1234567","invite-2",true)));
        assertEquals(2,child.depth());assertEquals(2L,child.inviterId());
    }
    @Test void adjustmentIsExactIdempotentScopedAndDoesNotSpendFrozenFunds(){
        Map<String,Object> input=fields("requestId","adjustment-one","currency","USDT","walletType","EARNINGS","direction","ADD","reason","SYSTEM","amount","100.00000001","remark","核对");
        var first=tx.execute(status->service.adjustMoney(1,input));assertEquals(data.json(first),data.json(tx.execute(status->service.adjustMoney(1,input))));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='ADMIN_SYSTEM'",Integer.class));
        jdbc.update("UPDATE compute_wallet SET frozen=9 WHERE member_id=1 AND currency='USDT' AND wallet_type='EARNINGS'");
        var subtract=new LinkedHashMap<>(input);subtract.put("requestId","adjustment-two");subtract.put("direction","SUBTRACT");subtract.put("amount","101");
        assertThrows(BusinessException.class,()->tx.execute(status->service.adjustMoney(1,subtract)));
        assertEquals(0,new BigDecimal("9").compareTo(jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=1 AND currency='USDT' AND wallet_type='EARNINGS'",BigDecimal.class)));
        login(2,"20103");assertThrows(BusinessException.class,()->tx.execute(status->service.adjustMoney(3,input)));
    }
    @Test void computeAdjustmentUpgradesWithConfiguredExpiryAndReplayDoesNotRenew() {
        jdbc.update("INSERT INTO compute_level(auto_provider_ids,id,name,threshold,enabled,vip_validity_days,vip_cycle_policy,required_direct_members,direct_member_deposit_threshold) VALUES('[]',2,'七天等级',100,1,7,'RESET',1,10)");
        Map<String,Object> firstInput=fields("requestId","vip-adjust-first","currency","USDT","walletType","COMPUTE","direction","ADD","reason","SYSTEM","amount","100");
        tx.executeWithoutResult(status->service.adjustMoney(1,firstInput));
        assertEquals(1L,jdbc.queryForObject("SELECT level FROM compute_member WHERE id=1",Long.class),"余额达标仍需有效直推人数达标");
        jdbc.update("UPDATE compute_level SET required_direct_members=0 WHERE id=2");
        Map<String,Object> upgradeInput=fields("requestId","vip-adjust-upgrade","currency","USDT","walletType","COMPUTE","direction","ADD","reason","SYSTEM","amount","1");
        var result=tx.execute(status->service.adjustMoney(1,upgradeInput));
        var member=rows(service.members(Map.of("memberId","1"))).get(0);
        assertEquals(2L,number(member.get("level")));
        assertNotNull(member.get("vipStartedAt"));assertNotNull(member.get("vipExpiresAt"));
        assertEquals(Instant.parse(text(member.get("vipStartedAt"))).plus(7,java.time.temporal.ChronoUnit.DAYS),Instant.parse(text(member.get("vipExpiresAt"))));
        assertEquals(data.json(result),data.json(tx.execute(status->service.adjustMoney(1,upgradeInput))));
        assertEquals(member.get("vipExpiresAt"),rows(service.members(Map.of("memberId","1"))).get(0).get("vipExpiresAt"));
        assertEquals(0,new BigDecimal("101").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='COMPUTE' AND currency='USDT'",BigDecimal.class)));
    }
    @Test void directVipEditReturnsGeneratedPeriodAndOtherEditsDoNotRenewIt() {
        jdbc.update("INSERT INTO compute_level(auto_provider_ids,id,name,threshold,enabled,vip_validity_days,vip_cycle_policy) VALUES('[]',2,'三十天等级',100,1,30,'RESET')");
        var result=tx.execute(status->service.updateMember(1,Map.of("level",2)));
        assertNotNull(result.get("vipStartedAt"));assertNotNull(result.get("vipExpiresAt"));
        assertEquals(Instant.parse(text(result.get("vipStartedAt"))).plus(30,java.time.temporal.ChronoUnit.DAYS),Instant.parse(text(result.get("vipExpiresAt"))));
        var edited=tx.execute(status->service.updateMember(1,Map.of("level",2,"remark","只改备注")));
        assertEquals(result.get("vipStartedAt"),edited.get("vipStartedAt"));
        assertEquals(result.get("vipExpiresAt"),edited.get("vipExpiresAt"));
        assertEquals(result.get("vipExpiresAt"),rows(service.members(Map.of("memberId","1"))).get(0).get("vipExpiresAt"));
    }
    @Test void approvedManualDepositUpgradesAtExactBalanceThresholdAndDoesNotRenewOnReplay() {
        jdbc.update("INSERT INTO compute_level(auto_provider_ids,id,name,threshold,enabled,vip_validity_days,vip_cycle_policy,required_direct_members,direct_member_deposit_threshold) VALUES('[]',2,'十元等级',10,1,180,'RESET',0,10)");
        var request=fields("memberId",2,"walletType","COMPUTE","currency","USDT","network","TRC20","amount","10","requestId","vip-deposit-exact-ten","remark","人工充值核对");
        var pending=tx.execute(status->service.manualDeposit(request));
        assertEquals(1L,jdbc.queryForObject("SELECT level FROM compute_member WHERE id=2",Long.class),"尚未审核入账不能升级");
        tx.executeWithoutResult(status->service.reviewDeposit(number(pending.get("id")),true,"通过",1));
        assertEquals(2L,jdbc.queryForObject("SELECT level FROM compute_member WHERE id=2",Long.class),"余额恰好达到10即升级，无需再加1");
        assertEquals(0,new BigDecimal("10").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=2 AND wallet_type='COMPUTE' AND currency='USDT'",BigDecimal.class)));
        var period=data.one("SELECT vip_started_at,vip_expires_at FROM compute_member WHERE id=2");
        assertNotNull(period.get("vipStartedAt"));assertNotNull(period.get("vipExpiresAt"));
        assertEquals(Instant.parse(text(period.get("vipStartedAt"))).plus(180,java.time.temporal.ChronoUnit.DAYS),Instant.parse(text(period.get("vipExpiresAt"))));
        assertEquals(data.json(pending),data.json(tx.execute(status->service.manualDeposit(request))));
        assertThrows(BusinessException.class,()->tx.execute(status->service.reviewDeposit(number(pending.get("id")),true,"重试",1)));
        assertEquals(period,data.one("SELECT vip_started_at,vip_expires_at FROM compute_member WHERE id=2"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE member_id=2 AND kind='DEPOSIT'",Integer.class));
    }
    @Test void depositUpgradeRetainsBalanceWalletAndDirectMemberRequirements() {
        jdbc.update("INSERT INTO compute_level(auto_provider_ids,id,name,threshold,enabled,vip_validity_days,vip_cycle_policy,required_direct_members,direct_member_deposit_threshold) VALUES('[]',2,'十元等级',10,1,180,'RESET',0,10)");
        var below=tx.execute(status->service.manualDeposit(fields("memberId",2,"walletType","COMPUTE","currency","USDT","network","TRC20","amount","9.99999999","requestId","vip-deposit-below-ten","remark","门槛前")));
        tx.executeWithoutResult(status->service.reviewDeposit(number(below.get("id")),true,"通过",1));
        assertEquals(1L,jdbc.queryForObject("SELECT level FROM compute_member WHERE id=2",Long.class));
        var project=tx.execute(status->service.manualDeposit(fields("memberId",2,"walletType","PROJECT","currency","USDT","network","TRC20","amount","10","requestId","vip-deposit-project-ten","remark","项目钱包")));
        tx.executeWithoutResult(status->service.reviewDeposit(number(project.get("id")),true,"通过",1));
        assertEquals(1L,jdbc.queryForObject("SELECT level FROM compute_member WHERE id=2",Long.class),"项目钱包余额不能满足算力门槛");
        jdbc.update("UPDATE compute_level SET required_direct_members=1 WHERE id=2");
        var exact=tx.execute(status->service.manualDeposit(fields("memberId",2,"walletType","COMPUTE","currency","USDT","network","TRC20","amount","0.00000001","requestId","vip-deposit-reaches-ten","remark","达到门槛")));
        tx.executeWithoutResult(status->service.reviewDeposit(number(exact.get("id")),true,"通过",1));
        assertEquals(1L,jdbc.queryForObject("SELECT level FROM compute_member WHERE id=2",Long.class),"达到余额门槛仍须保留有效直推人数校验");
        assertEquals(0,new BigDecimal("10").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=2 AND wallet_type='COMPUTE' AND currency='USDT'",BigDecimal.class)));
    }
    @Test void migrationDetectsCyclesStalePreviewAndRecomputesScope(){
        assertThrows(BusinessException.class,()->service.previewInviter(1,Map.of("targetMemberId",4)));
        var preview=service.previewInviter(2,Map.of("targetMemberId",3));
        var request=fields("requestId","move-child-1","targetMemberId",3,"expectedVersion",preview.get("version"),"remark","迁移");
        var moved=tx.execute(status->service.transferInviter(2,request));assertEquals(data.json(moved),data.json(tx.execute(status->service.transferInviter(2,request))));
        login(2,"20100");assertEquals(List.of(1L),ids(service.members(Map.of())));
        login(1,"super");assertThrows(BusinessException.class,()->tx.execute(status->service.transferInviter(1,fields("requestId","move-stale-1","targetMemberId",3,"expectedVersion",preview.get("version")))));
        assertEquals(3L,jdbc.queryForObject("SELECT inviter_id FROM compute_member WHERE id=2",Long.class));
        assertEquals(2L,jdbc.queryForObject("SELECT inviter_id FROM compute_member WHERE id=4",Long.class));
    }
    @Test void fixedUngroupedAndIndependentSearchAndDepthFilters(){
        assertEquals(4L,rows(service.groups(Map.of())).get(0).get("memberCount"));
        jdbc.update("UPDATE compute_member SET is_test=1,group_id=NULL WHERE id=4");
        assertEquals(4L,rows(service.groups(Map.of())).get(0).get("memberCount"));
        assertEquals(4L,number(service.groupMembers(0L,Map.of()).get("total")));
        assertEquals(List.of(2L),ids(service.members(Map.of("username","child","email","child@example","phone","+1000002","groupId","0","depth","1"))));
        assertEquals(List.of(4L),ids(service.members(Map.of("minDepth","2"))));
        assertThrows(BusinessException.class,()->service.saveGroup(Map.of("id",0,"name","覆盖")));
        Map<String,Object> group=tx.execute(status->service.saveGroup(Map.of("name","甲组","ownerMemberId",1)));
        assertEquals(0L,number(service.groups(Map.of("keyword","不存在")).get("total")));
        assertEquals(1,rows(service.groups(Map.of("size","1"))).size());
        assertEquals(List.of(number(group.get("id"))),ids(service.groups(Map.of("page","2","size","1"))));
        assertEquals(List.of(2L),ids(service.agents(Map.of("username","child","memberId","2","inviteCode","invite-2"))));
        assertEquals(1,((List<?>)service.agents(Map.of("parentMemberId","1")).get("ancestors")).size());
        tx.executeWithoutResult(status->service.assignGroup(fields("memberIds",List.of(1,2),"groupId",group.get("id"))));
        tx.executeWithoutResult(status->service.assignGroup(fields("memberIds",List.of(1,3),"groupId",group.get("id"))));
        assertEquals(number(group.get("id")),jdbc.queryForObject("SELECT group_id FROM compute_member WHERE id=3",Long.class));
        jdbc.update("UPDATE compute_member_group SET enabled=0 WHERE id=?",group.get("id"));
        jdbc.update("UPDATE compute_level SET enabled=0 WHERE id=1");
        tx.executeWithoutResult(status->service.updateMember(1,Map.of("enabled",false,"level",1)));
    }
    @Test void memberGroupMapperUpdatesKeepOwnershipAndJoinTheBusinessTransaction() {
        var created=tx.execute(status->service.saveGroup(fields("name","分组原名","ownerMemberId",1,"note","原备注")));
        long id=number(created.get("id"));assertTrue(id>0);
        var changed=tx.execute(status->service.saveGroup(fields("id",id,"name","分组新名","note","新备注","sortOrder",8,"enabled",false)));
        assertEquals("分组新名",changed.get("name"));assertEquals(1L,number(changed.get("ownerMemberId")));
        assertEquals(created.get("createdAt"),changed.get("createdAt"));assertEquals(8L,number(changed.get("sortOrder")));
        assertFalse(enabled(changed.get("enabled")));
        assertThrows(BusinessException.class,()->tx.execute(status->service.saveGroup(fields("id",id,"name","越权变更","ownerMemberId",2))));
        assertEquals("分组新名",jdbc.queryForObject("SELECT name FROM compute_member_group WHERE id=?",String.class,id));
        tx.executeWithoutResult(status->{service.saveGroup(fields("id",id,"name","回滚名称"));status.setRollbackOnly();});
        assertEquals("分组新名",jdbc.queryForObject("SELECT name FROM compute_member_group WHERE id=?",String.class,id));
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_group",Integer.class);
        tx.executeWithoutResult(status->{service.saveGroup(fields("name","回滚创建"));status.setRollbackOnly();});
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_group",Integer.class));
    }

    @Test void memberUidAndOnlineFiltersReuseLiveWindowAndRespectScope() {
        var observed=java.time.LocalDateTime.of(2026,9,28,12,0);
        var online=new ComputeOnlinePolicy(data,new org.token.service.auth.LoginSessionPolicy(jdbc),
                java.time.Clock.fixed(observed.toInstant(java.time.ZoneOffset.UTC),java.time.ZoneOffset.UTC));
        service=new ComputeManagementService(data,jdbc,scope,new ComputeCatalogService(data,new PasswordUtils()),business,null,
                new ComputeVipPeriodService(data,null),online);
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('liveMonitor','{\"onlineWindowMinutes\":10}')");
        for(long id:List.of(1L,2L,3L,4L))jdbc.update("INSERT INTO compute_member_session(token_hash,member_id,credential_hash,expires_at,last_seen_at) SELECT ?,id,password_hash,?,? FROM compute_member WHERE id=?",
                String.valueOf(id).repeat(64),observed.plusHours(1),observed.minusMinutes(id==4?11:id==1?10:1),id);
        login(2,"20100");
        assertEquals(List.of(2L),ids(service.members(Map.of("memberId","2","online","true"))));
        assertTrue(ids(service.members(Map.of("memberId","3","online","true"))).isEmpty());
        var first=service.members(Map.of("online","true","size","1"));
        assertEquals(2L,number(first.get("total")));assertEquals(List.of(2L),ids(first));
        assertEquals(true,rows(first).get(0).get("online"));
        assertEquals(List.of(1L),ids(service.members(Map.of("online","true","size","1","page","2"))));
        assertEquals(List.of(4L),ids(service.members(Map.of("online","false"))));
        jdbc.update("UPDATE compute_member_session SET expires_at=? WHERE member_id=1",observed);
        jdbc.update("UPDATE compute_member_session SET credential_hash='old-password' WHERE member_id=2");
        assertTrue(ids(service.members(Map.of("online","true"))).isEmpty());
        jdbc.update("UPDATE compute_member_session SET credential_hash=(SELECT password_hash FROM compute_member WHERE id=2) WHERE member_id=2");
        jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=2");
        assertTrue(ids(service.members(Map.of("online","true"))).isEmpty());
        assertThrows(BusinessException.class,()->service.members(Map.of("online","unknown")));
        assertThrows(BusinessException.class,()->service.members(Map.of("memberId","2x")));
    }
    @Test void memberLanguageNamesUseChineseAndKeepRegion() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS translate_language(lang VARCHAR(32),chinese_name VARCHAR(100),name VARCHAR(100),native_name VARCHAR(100))");
        jdbc.update("INSERT INTO translate_language(lang,chinese_name,name) VALUES('en-us','美式英语','English'),('pt-br','','Portuguese'),('ar','Arabic','Arabic')");
        jdbc.update("UPDATE compute_member SET user_language='en_US' WHERE id=1");
        jdbc.update("UPDATE compute_member SET user_language='pt-BR' WHERE id=2");
        assertEquals("美式英语",rows(service.members(Map.of("memberId","1"))).get(0).get("userLanguageName"));
        String portuguese=text(rows(service.members(Map.of("memberId","2"))).get(0).get("userLanguageName"));
        assertTrue(portuguese.contains("葡萄牙"));assertTrue(portuguese.contains("巴西"));
        jdbc.update("UPDATE compute_member SET user_language='ar' WHERE id=1");
        assertEquals("阿拉伯语",rows(service.members(Map.of("memberId","1"))).get(0).get("userLanguageName"));
        assertEquals("阿拉伯语",ComputeManagementService.chineseLanguageName("ar","Arabic"));
        assertTrue(ComputeManagementService.chineseLanguageName("ar_SA","Arabic").contains("沙特阿拉伯"));
        assertEquals("繁体中文",ComputeManagementService.chineseLanguageName("zh-Hant","繁体中文"));
        assertEquals("英语",ComputeManagementService.chineseLanguageName("en",null));
        assertEquals("",ComputeManagementService.chineseLanguageName(null,null));
    }
    @Test void securityProfileUsesBcryptAndBlankPasswordsDoNotOverwrite(){
        tx.executeWithoutResult(status->service.updateMember(1,fields("payPassword","654321","securityScore","88.50","remark","复核")));
        String hash=jdbc.queryForObject("SELECT pay_password_hash FROM compute_member WHERE id=1",String.class);
        assertTrue(new PasswordUtils().matchesPassword("654321",hash));assertEquals(true,accounts.member(1).get("hasPayPassword"));
        tx.executeWithoutResult(status->service.updateMember(1,Map.of("payPassword","","email","changed@example.invalid")));
        assertEquals(hash,jdbc.queryForObject("SELECT pay_password_hash FROM compute_member WHERE id=1",String.class));
        assertFalse(rows(service.members(Map.of("memberId","1"))).get(0).containsKey("payPasswordHash"));
        assertThrows(BusinessException.class,()->tx.execute(status->business.transfer(1,fields("requestId","pay-check-one","fromWallet","EARNINGS","toWallet","COMPUTE","currency","USDT","amount","1"))));
    }
    @Test void memberTokenIncomePercentageIsPersistedValidatedAndReturnedWithoutChangingFunds(){
        assertEquals(0, new BigDecimal("100").compareTo(jdbc.queryForObject("SELECT token_income_percent FROM compute_member WHERE id=1", BigDecimal.class)));
        var before = data.rows("SELECT * FROM compute_wallet ORDER BY id");
        var saved = tx.execute(status -> service.updateMember(1, fields("tokenIncomePercent", "90.25")));
        assertEquals("90.25", saved.get("tokenIncomePercent"));
        assertEquals("90.25", rows(service.members(Map.of("memberId", "1"))).get(0).get("tokenIncomePercent"));
        tx.executeWithoutResult(status -> service.updateMember(1, Map.of("remark", "只改备注")));
        assertEquals(new BigDecimal("90.25"), jdbc.queryForObject("SELECT token_income_percent FROM compute_member WHERE id=1", BigDecimal.class));
        for (Object value : Arrays.asList(null, "", "-0.01", "100.01", "90.001", "invalid")) {
            var error = assertThrows(BusinessException.class, () -> tx.execute(status -> service.updateMember(1, fields("tokenIncomePercent", value))));
            assertEquals("server.compute.v24.invalidTokenIncomePercent", error.getMessage());
        }
        tx.executeWithoutResult(status -> service.updateMember(1, Map.of("tokenIncomePercent", 0)));
        assertEquals(0, jdbc.queryForObject("SELECT token_income_percent FROM compute_member WHERE id=1", BigDecimal.class).signum());
        assertEquals(before, data.rows("SELECT * FROM compute_wallet ORDER BY id"));
    }
    @Test void reportUnifiesNewDepositsPreservesLegacyCurrencyAndFirstDepositUsesIdTieBreaker(){
        for(String value:List.of("20","30")){var d=tx.execute(status->business.deposit(2,fields("requestId","deposit-"+value,"walletType","COMPUTE","currency","USDT","network","TRC20","amount",value)));tx.executeWithoutResult(status->business.reviewDeposit(number(d.get("id")),true,"",1));}
        jdbc.update("UPDATE compute_deposit SET reviewed_at='2026-09-11 01:00:00' WHERE member_id=2");
        var other=tx.execute(status->business.deposit(2,fields("requestId","deposit-usdc","walletType","COMPUTE","currency","USDC","network","ERC20","amount","999")));
        tx.executeWithoutResult(status->business.reviewDeposit(number(other.get("id")),true,"",1));
        long legacy=data.insert("compute_deposit",fields("member_id",2,"wallet_type","COMPUTE","currency","USDC","network","ERC20",
                "amount",new BigDecimal("17"),"status","APPROVED","created_at",now(),"reviewed_at",now()));
        var row=rows(service.teamReport(Map.of("memberId","1","relationDepth","1","currency","USDT"))).get(0);
        assertFalse(row.containsKey("totalDeposits"));assertEquals("1049.00000000",currency(row,"USDT").get("totalDeposits"));assertEquals("20.00000000",currency(row,"USDT").get("firstDepositAmount"));
        assertEquals(0,new BigDecimal("17").compareTo(decimal(currency(row,"USDC").get("totalDeposits"))));
        assertEquals(0,decimal(currency(row,"USDC").get("firstDepositAmount")).signum());
        assertEquals(1L,number(row.get("firstDepositMembers")));assertEquals(1L,number(row.get("depositMembers")));assertEquals(4L,number(row.get("depositCount")));assertEquals(2L,number(row.get("memberCount")));
        assertEquals(1L,number(service.finances("deposits",Map.of("memberId","2","firstDeposit","true")).get("total")));
        assertEquals(1L,number(data.one("SELECT accounting_version FROM compute_deposit WHERE id=?",legacy).get("accountingVersion")));
        var inWindow=rows(service.teamReport(Map.of("memberId","1","currency","USDT","startTime","2026-09-11T00:00:00Z","endTime","2026-09-11T02:00:00Z"))).get(0);
        assertEquals("50.00000000",currency(inWindow,"USDT").get("totalDeposits"));
        var beforeReview=rows(service.teamReport(Map.of("memberId","1","currency","USDT","endTime","2026-09-11T00:00:00Z"))).get(0);
        assertEquals(0,decimal(currency(beforeReview,"USDT").get("totalDeposits")).compareTo(BigDecimal.ZERO));
        assertEquals(List.of(3L,1L),ids(service.teamReport(Map.of())));
        for(long member:List.of(2L,4L))for(String currency:List.of("USDT","USDC")) {
            data.insert("compute_order",fields("member_id",member,"product_id",1,"product_name","产品统计","product_type","GPU","source_wallet","PROJECT","currency",currency,
                    "amount",new BigDecimal("50"),"daily_rate",new BigDecimal("0.1"),"duration_days",30,"settlement_mode","A","product_snapshot","{}",
                    "expected_income",BigDecimal.ONE,"paid_income",BigDecimal.ZERO,"settled_days",0,"status","ACTIVE","starts_at",java.time.LocalDateTime.parse("2026-09-11T01:00:00"),
                    "matures_at",java.time.LocalDateTime.parse("2026-10-11T01:00:00"),"created_at",java.time.LocalDateTime.parse("2026-09-11T01:00:00")));
        }
        var productWindow=rows(service.teamReport(Map.of("memberId","1","currency","USDT","startTime","2026-09-11T00:00:00Z","endTime","2026-09-11T02:00:00Z"))).get(0);
        assertFalse(productWindow.containsKey("productCount"));assertFalse(currency(productWindow,"USDT").containsKey("productAmount"));
        var beforePurchase=rows(service.teamReport(Map.of("memberId","1","currency","USDT","endTime","2026-09-11T00:00:00Z"))).get(0);
        assertFalse(beforePurchase.containsKey("productCount"));assertFalse(currency(beforePurchase,"USDT").containsKey("activePrincipal"));
        tx.executeWithoutResult(status->service.updateMember(2,Map.of("isTest",true)));
        var throughTest=rows(service.teamReport(Map.of("memberId","1","currency","USDT","relationDepth","2"))).get(0);
        assertFalse(throughTest.containsKey("productMembers"));assertFalse(currency(throughTest,"USDT").containsKey("productAmount"));
        jdbc.update("UPDATE compute_order SET expected_income=3,paid_income=3 WHERE member_id=2 AND currency='USDT'");
        long testOrder=jdbc.queryForObject("SELECT id FROM compute_order WHERE member_id=2 AND currency='USDT'",Long.class);
        data.insert("compute_order_settlement",fields("order_id",testOrder,"member_id",2,"settlement_day",1,"settlement_date",java.time.LocalDate.now(java.time.ZoneOffset.UTC),"income",BigDecimal.ONE,"principal",BigDecimal.ZERO,"created_at",now()));
        var personal=business.dashboard(2);
        assertEquals(0,new BigDecimal("50").compareTo(decimal(personal.get("activePrincipal"))));
        assertEquals(0,new BigDecimal("3").compareTo(decimal(personal.get("totalIncome"))));
        assertEquals(0,BigDecimal.ONE.compareTo(decimal(personal.get("todayIncome"))));
        assertEquals(2,((List<?>)personal.get("currencySummaries")).size());
        assertFalse(currency(rows(service.teamReport(Map.of("memberId","1"))).get(0),"USDT").containsKey("totalIncome"));
    }
    @Test void teamReportDoesNotReadRemovedInvestmentAndIncomeAggregates() {
        jdbc.execute("ALTER TABLE compute_order RENAME TO omitted_team_order");
        jdbc.execute("ALTER TABLE compute_auto_contract RENAME TO omitted_team_contract");
        jdbc.execute("ALTER TABLE compute_ledger RENAME TO omitted_team_ledger");
        var team=rows(service.teamReport(Map.of("memberId","1"))).get(0);
        assertEquals(3L,number(team.get("memberCount")));
        for (String key : List.of("activePrincipal","totalIncome","productCount","autoCount","averageDeposit")) {
            assertFalse(team.containsKey(key));assertFalse(currency(team,"USDT").containsKey(key));
        }
    }

    @Test void overviewUsesConfiguredNaturalDay(){
        jdbc.update("UPDATE compute_member SET created_at='2026-09-10 17:00:00' WHERE id=1");
        jdbc.update("UPDATE compute_member SET created_at='2026-09-01 01:00:00' WHERE id<>1");
        var result=service.overviewAt(Map.of(),Instant.parse("2026-09-10T18:00:00Z"));assertEquals(1L,number(result.get("todayMembers")));
    }
    @Test void channelQuoteSnapshotAndRecipientConfirmationAreAtomic(){
        jdbc.update("UPDATE compute_member SET pay_password_hash=? WHERE id IN (1,3)",new PasswordUtils().hashPassword("Pay123456"));
        jdbc.update("UPDATE compute_currency SET need_memo=1 WHERE currency='USDT' AND network='TRC20'");
        jdbc.update("INSERT INTO compute_fee_rule(name,rate,fixed_fee,updated_at) VALUES('高级',10,1,?)",now());
        Map<String,Object> quoteInput=fields("currency","USDT","network","TRC20","amount","20");
        var advanced=business.quote(1,quoteInput);assertEquals("ADVANCED_RULE",advanced.get("feeSource"));assertEquals("3.00000000",advanced.get("fee"));
        jdbc.update("UPDATE compute_currency SET fee_mode='CHANNEL',withdraw_fee_fixed=1,withdraw_fee_rate=0.025 WHERE currency='USDT'");
        var channel=business.quote(1,quoteInput);assertEquals("CHANNEL",channel.get("feeSource"));assertEquals("1.50000000",channel.get("fee"));assertNull(channel.get("ruleId"));
        String address="T"+"1".repeat(33);
        var first=tx.execute(status->business.saveAddress(1,fields("currency","USDT","network","TRC20","address",address,"memo","one","label","地址一","payPassword","Pay123456")));
        var second=tx.execute(status->business.saveAddress(1,fields("currency","USDT","network","TRC20","address",address,"memo","two","label","地址二","payPassword","Pay123456")));
        assertNotEquals(first.get("id"),second.get("id"));
        jdbc.update("UPDATE compute_wallet SET available=100 WHERE member_id=1 AND wallet_type='EARNINGS' AND currency='USDT'");
        Map<String,Object> request=new LinkedHashMap<>(quoteInput);request.putAll(fields("requestId","channel-withdraw-one","expectedFee",channel.get("fee"),"expectedNetAmount",channel.get("netAmount"),"addressId",first.get("id"),"address",address,"memo","one","payPassword","Pay123456"));
        request.putAll(fields("expectedAccountingRate",channel.get("accountingRate"),"expectedAccountingAmount",channel.get("accountingAmount")));
        jdbc.update("UPDATE compute_currency SET withdraw_fee_fixed=2 WHERE currency='USDT'");
        assertEquals("server.compute.quoteChanged",assertThrows(BusinessException.class,()->tx.execute(status->business.withdraw(1,request))).getMessage());
        assertEquals(0,new BigDecimal("100").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS' AND currency='USDT'",BigDecimal.class)));
        jdbc.update("UPDATE compute_currency SET withdraw_fee_fixed=1 WHERE currency='USDT'");
        jdbc.update("UPDATE compute_address SET memo='changed' WHERE id=?",first.get("id"));
        assertEquals("server.compute.recipientChanged",assertThrows(BusinessException.class,()->tx.execute(status->business.withdraw(1,request))).getMessage());
        jdbc.update("UPDATE compute_address SET memo='one' WHERE id=?",first.get("id"));
        var withdrawal=tx.execute(status->business.withdraw(1,request));assertNotNull(withdrawal.get("addressSnapshot"));
        assertEquals("one",data.jsonMap(text(withdrawal.get("addressSnapshot"))).get("memo"));
        assertEquals(data.json(withdrawal),data.json(tx.execute(status->business.withdraw(1,request))));
        Map<String,Object> foreign=new LinkedHashMap<>(request);foreign.put("requestId","foreign-address-one");
        assertThrows(BusinessException.class,()->tx.execute(status->business.withdraw(3,foreign)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_withdrawal",Integer.class));
    }

    @Test void withdrawalRulesUseExplicitTypePrecedenceAndWholeAmountTiers(){
        jdbc.update("UPDATE compute_currency SET fee_mode='ADVANCED_RULE' WHERE currency='USDT'");
        long general=data.insert("compute_fee_rule",fields("name","高优先级全局","rule_type","GLOBAL","priority",10000,"rate",10,"updated_at",now()));
        long tier=data.insert("compute_fee_rule",fields("name","金额阶梯","rule_type","AMOUNT","priority",5000,"min_amount",10,"max_amount",100,"rate",8,"updated_at",now()));
        long channel=data.insert("compute_fee_rule",fields("name","币网规则","rule_type","CHANNEL","priority",1000,"currency","USDT","network","TRC20","rate",6,"updated_at",now()));
        long vip=data.insert("compute_fee_rule",fields("name","等级规则","rule_type","LEVEL","priority",0,"level",1,"rate",2,"updated_at",now()));
        var applicable=fields("currency","USDT","network","TRC20","amount","50");
        var vipQuote=business.quote(1,applicable);
        assertEquals(vip,number(vipQuote.get("ruleId")));
        assertEquals(0,BigDecimal.ONE.compareTo(decimal(vipQuote.get("fee"))));

        long personal=data.insert("compute_fee_rule",fields("name","用户专属","rule_type","PERSONAL","priority",-100,"member_id",1,"rate",1,"updated_at",now()));
        var personalQuote=business.quote(1,applicable);
        assertEquals(personal,number(personalQuote.get("ruleId")));
        assertEquals(0,new BigDecimal("0.5").compareTo(decimal(personalQuote.get("fee"))));
        assertEquals(vip,number(business.quote(3,applicable).get("ruleId")),"其他用户不能套用个人规则");

        jdbc.update("UPDATE compute_fee_rule SET enabled=0 WHERE id IN (?,?)",personal,vip);
        assertEquals(channel,number(business.quote(1,applicable).get("ruleId")),"币网规则优先于更高priority的金额档");
        jdbc.update("UPDATE compute_fee_rule SET enabled=0 WHERE id=?",channel);
        for (String amount : List.of("10", "50", "100")) {
            var quote = business.quote(1,fields("currency","USDT","network","TRC20","amount",amount));
            assertEquals(tier,number(quote.get("ruleId")));
            assertEquals(0,decimal(amount).multiply(new BigDecimal("0.08")).compareTo(decimal(quote.get("fee"))),"整笔金额落档，不分段累计");
        }
        var fallback=business.quote(1,fields("currency","USDT","network","TRC20","amount","100.00000001"));
        assertEquals(general,number(fallback.get("ruleId")));
        assertEquals(0,new BigDecimal("10.00000001").compareTo(decimal(fallback.get("fee"))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_withdrawal",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }

    @Test void withdrawalRuleFormsPersistOnlyTheSelectedTargetAndRejectEmptyTiers(){
        var catalog = new ComputeCatalogService(data,new PasswordUtils());
        var request = fields("name","规则","enabled",true,"ruleType","GLOBAL","username","alpha","level",1,
                "channelId",1,"minAmount","10","maxAmount","100","rate","2","minimumFee","1",
                "lowAmountThreshold","999","lowAmountFee","100","openWeekdays",List.of(5,1));
        var global=tx.execute(status->catalog.save("fee-rules",request));
        assertNull(global.get("memberId")); assertNull(global.get("level"));
        assertEquals("*",global.get("currency")); assertEquals("*",global.get("network"));
        assertEquals(0,decimal(global.get("minAmount")).signum()); assertNull(global.get("maxAmount"));
        assertEquals(List.of(1,5),global.get("openWeekdays"));
        assertFalse(global.containsKey("lowAmountThreshold"));
        assertFalse(global.containsKey("lowAmountFee"));
        request.put("id",global.get("id"));request.put("ruleType","PERSONAL");
        var personal=tx.execute(status->catalog.save("fee-rules",request));
        assertEquals(1,number(personal.get("memberId")));assertNull(personal.get("level"));assertNull(personal.get("maxAmount"));
        request.put("ruleType","AMOUNT");request.put("minAmount","0");request.put("maxAmount",null);
        assertEquals("server.compute.invalidAmount",assertThrows(BusinessException.class,()->tx.execute(status->catalog.save("fee-rules",request))).getMessage());
        request.put("ruleType","GLOBAL");request.put("openWeekdays",List.of());
        assertEquals("server.compute.invalidTimeWindow",assertThrows(BusinessException.class,()->tx.execute(status->catalog.save("fee-rules",request))).getMessage());
        assertEquals("PERSONAL",jdbc.queryForObject("SELECT rule_type FROM compute_fee_rule WHERE id=?",String.class,global.get("id")));
    }

    @Test void writeResponsesDoNotRevealInvitersOutsideBoundRoots(){
        add(5,"other-root",null);
        jdbc.update("UPDATE compute_member SET inviter_id=3 WHERE id=1");
        tx.executeWithoutResult(status->service.saveBinding(Map.of("adminUid",2,"memberIds",List.of(1,5))));
        login(2,"20102","20104");
        var updated=tx.execute(status->service.updateMember(1,Map.of("remark","保持范围")));
        assertNull(updated.get("inviterId"));assertNull(updated.get("inviterName"));assertNull(updated.get("agentId"));
        var ancestors=(List<?>)service.agents(Map.of("parentMemberId","1")).get("ancestors");
        assertEquals(1,ancestors.size());assertEquals(1L,number(((Map<?,?>)ancestors.get(0)).get("id")));
        assertNull(((Map<?,?>)ancestors.get(0)).get("inviterId"));
        var preview=service.previewInviter(1,Map.of("targetMemberId",5));assertNull(preview.get("oldInviter"));
        var migrated=tx.execute(status->service.transferInviter(1,fields("targetMemberId",5,"requestId","private-move-one","expectedVersion",preview.get("version"))));
        assertNull(migrated.get("oldInviterId"));assertEquals(5L,number(migrated.get("inviterId")));
        assertEquals(5L,jdbc.queryForObject("SELECT inviter_id FROM compute_member WHERE id=1",Long.class));
    }

    @Test void replayAfterScopeShrinksMasksBothHistoricalParentsWithoutChangingAudit(){
        login(3,"20104");
        var preview=service.previewInviter(2,Map.of("targetMemberId",3));
        var request=fields("targetMemberId",3,"requestId","scope-shrink-replay","expectedVersion",preview.get("version"));
        var original=tx.execute(status->service.transferInviter(2,request));
        assertEquals(1L,number(original.get("oldInviterId")));assertEquals(3L,number(original.get("inviterId")));
        login(1,"super");tx.executeWithoutResult(status->service.saveBinding(Map.of("adminUid",3,"memberIds",List.of(2))));
        login(3,"20104");var replay=tx.execute(status->service.transferInviter(2,request));
        assertNull(replay.get("oldInviterId"));assertNull(replay.get("inviterId"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_operation",Integer.class));
        assertEquals(3L,jdbc.queryForObject("SELECT inviter_id FROM compute_member WHERE id=2",Long.class));
        var audit=data.jsonMap(jdbc.queryForObject("SELECT result_json FROM compute_member_operation WHERE request_id='scope-shrink-replay'",String.class));
        assertEquals(1L,number(audit.get("oldInviterId")));assertEquals(3L,number(audit.get("inviterId")));
    }

    @Test void defaultCurrencyCreatesOnlyThreeWalletsAndKeepsHistoricalBalances(){
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet WHERE member_id=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet WHERE member_id=1 AND currency='USDC'",Integer.class));
        assertEquals(3,tx.execute(status->business.wallets(1)).size());
        tx.executeWithoutResult(status->business.creditActivityReward(1,"USDC",new BigDecimal("3.00000001"),9001));
        tx.executeWithoutResult(status->business.creditActivityReward(1,"USDT",new BigDecimal("3.00000001"),9001));
        jdbc.update("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen,updated_at) VALUES(1,'PROJECT','USDC',0,0,?)",now());
        jdbc.update("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen,updated_at) VALUES(1,'COMPUTE','USDC',0,2,?)",now());
        jdbc.update("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen,updated_at) VALUES(1,'EARNINGS','USDC',5.00000002,0,?)",now());
        var personal=tx.execute(status->business.wallets(1));
        assertEquals(5,personal.size());assertFalse(personal.stream().anyMatch(row->"USDC".equals(row.get("currency"))&&"PROJECT".equals(row.get("type"))));
        assertEquals(5,((List<?>)business.dashboard(1).get("wallets")).size());
        assertEquals(6,((List<?>)service.memberWallets(1).get("wallets")).size());
        jdbc.update("UPDATE system_setting SET setting_value='USDC' WHERE setting_key='summaryCurrency'");
        var switched=tx.execute(status->business.wallets(1));
        assertEquals(5,switched.size());assertEquals(3,switched.stream().filter(row->"USDT".equals(row.get("currency"))).count());
        assertEquals("USDT",business.defaultCurrency());
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet WHERE member_id=1",Integer.class));
        assertEquals(0,new BigDecimal("3.00000001").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND currency='USDT' AND wallet_type='EARNINGS'",BigDecimal.class)));
        assertEquals(0,new BigDecimal("5.00000002").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND currency='USDC' AND wallet_type='EARNINGS'",BigDecimal.class)));
        assertEquals(0,new BigDecimal("2").compareTo(jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=1 AND currency='USDC' AND wallet_type='COMPUTE'",BigDecimal.class)));
        tx.executeWithoutResult(status->business.creditActivityReward(1,"USDT",BigDecimal.ONE,9002));
        assertEquals(5,tx.execute(status->business.wallets(1)).size());
        assertEquals(0,new BigDecimal("4.00000001").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND currency='USDT' AND wallet_type='EARNINGS'",BigDecimal.class)));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE member_id=1 AND currency='USDT'",Integer.class));
        add(5,"defaultusd",null);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet WHERE member_id=5 AND currency='USDC'",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet WHERE member_id=5 AND currency='USDT'",Integer.class));
    }

    @Test void testNodesCountAsMembersAndTransactionsButOnlyFormalAmountsAreAggregated(){
        tx.executeWithoutResult(status->service.updateMember(2,Map.of("isTest",true)));
        for(long member:List.of(2L,4L)){
            var deposit=tx.execute(status->service.manualDeposit(fields("memberId",member,"walletType","COMPUTE","network","TRC20","amount",member==2?"900":"40","requestId","test-tree-"+member,"remark","统计核对")));
            tx.executeWithoutResult(status->service.reviewDeposit(number(deposit.get("id")),true,"确认",1));
        }
        var whole=rows(service.teamReport(Map.of("memberId","1"))).get(0);
        assertEquals(3L,number(whole.get("memberCount")));assertEquals(0,new BigDecimal("40").compareTo(decimal(currency(whole,"USDT").get("totalDeposits"))));
        assertEquals(2L,number(whole.get("depositMembers")));assertEquals(2L,number(whole.get("depositCount")));
        assertEquals(2L,number(currency(whole,"USDT").get("depositMembers")));
        assertFalse(currency(whole,"USDT").containsKey("averageDeposit"),"团队报表不再返回已移除均值");
        assertEquals(2L,number(rows(service.teamReport(Map.of("memberId","1","relationDepth","1"))).get(0).get("memberCount")));
        assertEquals(3L,number(rows(service.teamReport(Map.of("memberId","1","relationDepth","2"))).get(0).get("memberCount")));
        assertEquals(List.of(3L,1L),ids(service.teamReport(Map.of())));
        var testEntry=rows(service.teamReport(Map.of("memberId","2"))).get(0);
        assertEquals(2L,number(testEntry.get("memberCount")));assertEquals(0,new BigDecimal("40").compareTo(decimal(currency(testEntry,"USDT").get("totalDeposits"))));
        assertEquals(List.of(4L,2L),ids(service.teamReport(Map.of("teamMemberId","2"))));
        var overview=service.overview(Map.of());assertEquals(4L,number(overview.get("memberCount")));assertEquals(0,new BigDecimal("40").compareTo(decimal(currency(overview,"USDT").get("totalDeposits"))));
        // 用户列表是单个真实账户的管理视图，须显示测试钱包；上面的报表仍只统计正式下级的40。
        var testMember=rows(service.members(Map.of("memberId","2"))).get(0);assertEquals(true,testMember.get("isTest"));assertEquals(0,new BigDecimal("900").compareTo(decimal(testMember.get("available"))));
        assertEquals(1L,number(service.finances("deposits",Map.of("memberId","2")).get("total")));
        var detail=((List<?>)service.memberWallets(2).get("wallets")).stream().map(item->(Map<?,?>)item).filter(row->"USDT".equals(row.get("currency"))&&"COMPUTE".equals(row.get("walletType"))).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("900").compareTo(decimal(detail.get("available"))));
        assertEquals(List.of(4L,2L,1L),ids(service.members(Map.of("teamMemberId","1"))));
    }

    @Test void physicalRootAndDirectParentSearchNeverUseTheBoundEntryAsTop(){
        jdbc.update("UPDATE compute_member SET inviter_id=3 WHERE id=1");
        assertEquals(List.of(4L,3L,2L,1L),ids(service.members(Map.of("topMemberId","3","topName","bravo"))));
        assertEquals(List.of(4L),ids(service.members(Map.of("inviterName","child"))));
        var global=rows(service.members(Map.of("memberId","4"))).get(0);assertEquals(2L,number(global.get("inviterId")));assertEquals(3L,number(global.get("topMemberId")));
        login(2,"20100");var restricted=rows(service.members(Map.of("memberId","1","inviterId","3","topName","bravo"))).get(0);
        assertNull(restricted.get("inviterId"));assertNull(restricted.get("topMemberId"));assertEquals(1L,number(restricted.get("id")));
    }

    @Test void groupAssignmentAcceptsOrdinaryMembersAcrossTeamsAndIsAtomicWithinScope(){
        var group=tx.execute(status->service.saveGroup(fields("name","普通分组","ownerMemberId",2)));
        tx.executeWithoutResult(status->service.assignGroup(fields("groupId",group.get("id"),"memberIds",List.of(1,3,4))));
        assertEquals(List.of(4L,3L,1L),ids(service.groupMembers(number(group.get("id")),Map.of())));
        assertEquals(List.of(4L,2L),ids(service.groupMembers(null,Map.of("usernames","child,grandchild"))));
        assertFalse(rows(service.groupMembers(null,Map.of())).get(0).containsKey("email"));
        login(2,"20201");assertThrows(BusinessException.class,()->tx.execute(status->service.assignGroup(fields("groupId",0,"memberIds",List.of(1,3)))));
        assertEquals(number(group.get("id")),jdbc.queryForObject("SELECT group_id FROM compute_member WHERE id=1",Long.class));
        tx.executeWithoutResult(status->service.assignGroup(fields("groupId",0,"memberIds",List.of(1,4))));
        assertNull(jdbc.queryForObject("SELECT group_id FROM compute_member WHERE id=4",Long.class));
    }

    @Test void manualFinanceIsIdempotentScopedAndKeepsOriginalCurrencyWhileCreditingUsd(){
        var request=fields("memberId",2,"walletType","PROJECT","currency","USDC","network","ERC20","amount","13.00000001","requestId","manual-default-currency","remark","凭证录入");
        jdbc.update("UPDATE system_setting SET setting_value='USDC' WHERE setting_key='summaryCurrency'");
        var created=tx.execute(status->service.manualDeposit(request));assertEquals("USDC",created.get("currency"));assertEquals("ADMIN_MANUAL",created.get("recordSource"));assertEquals(1L,number(created.get("createdBy")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE member_id=2",Integer.class));
        jdbc.update("UPDATE system_setting SET setting_value='USDT' WHERE setting_key='summaryCurrency'");
        assertEquals(data.json(created),data.json(tx.execute(status->service.manualDeposit(request))));
        tx.executeWithoutResult(status->service.reviewDeposit(number(created.get("id")),true,"凭证核实",1));
        assertEquals(0,new BigDecimal("13.00000001").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=2 AND currency='USDT' AND wallet_type='PROJECT'",BigDecimal.class)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet WHERE member_id=2 AND currency='USDC'",Integer.class));
        assertEquals("USDT",jdbc.queryForObject("SELECT currency FROM compute_ledger WHERE member_id=2",String.class));
        assertEquals(1L,number(service.finances("deposits",Map.of()).get("total")));assertEquals(1L,number(service.finances("deposits",Map.of("currency","USDC")).get("total")));
        assertThrows(BusinessException.class,()->tx.execute(status->service.reviewDeposit(number(created.get("id")),true,"再次",1)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE member_id=2",Integer.class));
        login(2,"30101");var foreign=new LinkedHashMap<>(request);foreign.put("memberId",3);foreign.put("requestId","foreign-manual-one");assertThrows(BusinessException.class,()->tx.execute(status->service.manualDeposit(foreign)));
    }

    @Test void manualTestWithdrawalFreezesAndCompletesWithoutExternalPayout(){
        tx.executeWithoutResult(status->service.updateMember(2,Map.of("isTest",true,"payPassword","654321")));
        jdbc.update("UPDATE compute_currency SET fee_mode='CHANNEL',withdraw_fee_fixed=1 WHERE currency='USDT'");
        jdbc.update("UPDATE compute_wallet SET available=100 WHERE member_id=2 AND currency='USDT' AND wallet_type='EARNINGS'");
        var input=fields("memberId",2,"network","TRC20","amount","20");var quote=service.manualWithdrawalQuote(input);
        input.putAll(fields("address","T"+"1".repeat(33),"expectedFee",quote.get("fee"),"expectedNetAmount",quote.get("netAmount"),"requestId","manual-test-withdraw","remark","测试流程"));
        var created=tx.execute(status->service.manualWithdrawal(input));assertEquals(data.json(created),data.json(tx.execute(status->service.manualWithdrawal(input))));
        assertEquals(0,new BigDecimal("20").compareTo(jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=2 AND currency='USDT' AND wallet_type='EARNINGS'",BigDecimal.class)));
        var done=tx.execute(status->service.reviewWithdrawal(number(created.get("id")),true,"测试确认",1));assertEquals("TEST_COMPLETED",done.get("completionMode"));assertEquals("APPROVED",done.get("status"));
        assertEquals(0,decimal(jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=2 AND currency='USDT' AND wallet_type='EARNINGS'",BigDecimal.class)).signum());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE member_id=2",Integer.class));
        assertThrows(BusinessException.class,()->tx.execute(status->service.reviewWithdrawal(number(created.get("id")),true,"重审",1)));
    }

    @Test void retiredGeneralDepositRateCannotCreateNewRewardsForTestOrFormalUsers(){
        jdbc.update("UPDATE system_setting SET setting_value='10' WHERE setting_key='depositRewardRate'");
        tx.executeWithoutResult(status->service.updateMember(2,Map.of("isTest",true)));
        var test=tx.execute(status->service.manualDeposit(fields("memberId",2,"walletType","COMPUTE","network","TRC20","amount","100","requestId","test-reward-none","remark","测试")));
        tx.executeWithoutResult(status->service.reviewDeposit(number(test.get("id")),true,"",1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award",Integer.class));
        var real=tx.execute(status->service.manualDeposit(fields("memberId",4,"walletType","COMPUTE","network","TRC20","amount","100","requestId","real-reward-through-test","remark","正式")));
        tx.executeWithoutResult(status->service.reviewDeposit(number(real.get("id")),true,"",1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award",Integer.class));
    }

    @Test void onlyExplicitAdminCreationMakesTestUsersAndInvitedRegistrationRemainsFormal(){
        var test=tx.execute(status->accounts.createFromAdmin(new ComputeMemberCreateRequest("testing",null,null,"1234567",null,true,true)));
        assertTrue(jdbc.queryForObject("SELECT is_test FROM compute_member WHERE id=?",Boolean.class,test.id()));
        var registered=tx.execute(status->accounts.register(new ComputeAuthRequests.Register("formalchild",null,null,"1234567",test.inviteCode())));
        long id=number(((Map<?,?>)registered.get("member")).get("id"));
        assertFalse(jdbc.queryForObject("SELECT is_test FROM compute_member WHERE id=?",Boolean.class,id));
        assertEquals(test.id(),jdbc.queryForObject("SELECT inviter_id FROM compute_member WHERE id=?",Long.class,id));
    }

    @Test void addressLimitsPreserveExistingRowsAndNetworksRemainIndependent(){
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('addressLimit','1')");
        tx.executeWithoutResult(status->service.updateMember(2,Map.of("payPassword","Pay123456")));
        var tron=fields("username","child","currency","USDT","network","TRC20","label","一","address","T"+"1".repeat(33),"payPassword","Pay123456");
        var first=tx.execute(status->service.saveAddress(null,tron));
        assertEquals(first.get("id"),tx.execute(status->service.saveAddress(null,tron)).get("id"));
        var evm=fields("username","child","currency","USDC","network","ERC20","label","二","address","0x"+"1".repeat(40),"payPassword","Pay123456");
        assertEquals("server.compute.v6.addressLimitReached",assertThrows(BusinessException.class,()->tx.execute(status->service.saveAddress(null,evm))).getMessage());
        tx.executeWithoutResult(status->service.updateMember(2,Map.of("addressLimit",2)));
        var second=tx.execute(status->service.saveAddress(null,evm));
        assertEquals(List.of(number(second.get("id"))),ids(service.addresses(Map.of("username","child","network","ERC20"))));
        tx.executeWithoutResult(status->service.updateMember(2,Map.of("addressLimit",0)));
        tx.executeWithoutResult(status->service.saveAddress(number(first.get("id")),Map.of("label","保留旧地址")));
        tx.executeWithoutResult(status->business.deleteAddress(2,number(first.get("id"))));
        assertThrows(BusinessException.class,()->tx.execute(status->service.saveAddress(null,tron)));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address WHERE member_id=2",Integer.class));
    }

    @Test void invitationRewardFilterIncludesBothSourcesAndKeepsAgentScope(){
        for(long member:List.of(2L,3L))for(String kind:List.of("TEAM_REWARD","TEAM_REWARD_TEST","ACTIVITY_REWARD")) {
            data.insert("compute_ledger",fields("order_no","LG"+member+kind,"member_id",member,"wallet_type","EARNINGS","currency","USDT","kind",kind,
                    "amount",1,"frozen_change",0,"available_after",1,"frozen_after",0,"reference_id","fixture","event_key",member+":"+kind,"created_at",now()));
        }
        login(2,"30300");
        var filtered=rows(service.finances("ledger",Map.of("kind","TEAM_REWARD")));
        assertEquals(2,filtered.size());
        assertTrue(filtered.stream().allMatch(row->number(row.get("memberId"))==2));
        assertEquals(Set.of("TEAM_REWARD","TEAM_REWARD_TEST"),new HashSet<>(filtered.stream().map(row->text(row.get("kind"))).toList()));
        assertEquals(1,rows(service.finances("ledger",Map.of("kind","ACTIVITY_REWARD"))).size());
    }

    @Test void financialRowsExposeUserAndRealChainEvidenceWithoutRewritingHistoricalNumbers(){
        var deposit=tx.execute(status->service.manualDeposit(fields("username","child","walletType","COMPUTE","amount","10","remark","人工凭证","requestId","v8-chain-deposit")));
        long id=number(deposit.get("id"));
        var initial=rows(service.finances("deposits",Map.of("username","child"))).get(0);
        assertEquals(2L,number(initial.get("uid")));
        assertEquals("child",initial.get("username"));
        assertEquals("NOT_APPLICABLE",initial.get("chainStatus"));
        assertNull(initial.get("txHash"));
        String historical="DP"+"A".repeat(32);
        jdbc.update("UPDATE compute_deposit SET order_no=?,sender_address='sender-proof',receiver_address='receiver-proof',tx_hash='real-chain-proof',chain_status='CONFIRMED',chain_confirmations=20 WHERE id=?",historical,id);
        var stored=rows(service.finances("deposits",Map.of("txHash","real-chain-proof"))).get(0);
        assertEquals(historical,stored.get("orderNo"));
        assertEquals("sender-proof",stored.get("senderAddress"));
        assertEquals("receiver-proof",stored.get("receiverAddress"));
        assertEquals(20L,number(stored.get("chainConfirmations")));
        assertEquals(0L,number(service.finances("deposits",Map.of("txHash","missing-proof")).get("total")));
        assertThrows(BusinessException.class,()->service.finances("ledger",Map.of("txHash","real-chain-proof")));
    }

    @Test void depositAndWithdrawalPagesUseTheMembersStoredLastLoginCountryWithinScope() {
        jdbc.update("UPDATE compute_member SET registration_country='US',last_login_country='CN' WHERE id=2");
        jdbc.update("UPDATE compute_member SET last_login_country='JP' WHERE id IN (1,3)");
        jdbc.update("UPDATE compute_member SET last_login_country=NULL WHERE id=4");
        for(String kind:List.of("DEPOSIT","WITHDRAWAL")) {
            historyPayment(kind,2,"USDT","PENDING",now());
            historyPayment(kind,3,"USDT","PENDING",now());
            historyPayment(kind,4,"USDT","PENDING",now());
        }
        login(2,"30100","30200","20100");
        Object memberCountry=rows(service.members(Map.of("memberId","2"))).get(0).get("lastLoginCountry");
        assertEquals("CN",memberCountry);
        for(String resource:List.of("deposits","withdrawals")) {
            var first=service.finances(resource,Map.of("page","1","size","1"));
            assertEquals(2L,number(first.get("total")),"分页总数仍只含授权代理线会员");
            assertEquals(4L,number(rows(first).get(0).get("memberId")));
            assertNull(rows(first).get(0).get("lastLoginCountry"),"未保存登录国家不能猜成注册国或上级国家");
            var second=rows(service.finances(resource,Map.of("page","2","size","1"))).get(0);
            assertEquals(2L,number(second.get("memberId")));
            assertEquals(memberCountry,second.get("lastLoginCountry"));
            assertTrue(rows(service.finances(resource,Map.of("memberId","3"))).isEmpty());
        }
        assertEquals("CN",jdbc.queryForObject("SELECT last_login_country FROM compute_member WHERE id=2",String.class));
    }

    @SuppressWarnings("unchecked")
    @Test void withdrawalHistoryTotalsBatchVisibleUsersAndKeepFixedUsdNetAmounts() {
        var mapper=org.mockito.Mockito.mock(org.token.repository.dao.compute.ComputeManagementMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(data.mapper(org.token.repository.dao.compute.ComputeManagementMapper.class)));
        var repository=new org.token.repository.compute.ComputeManagementRepository(mapper,
                data.mapper(org.token.repository.dao.compute.ComputeMemberGroupMapper.class));
        service=new ComputeManagementService(data,repository,scope,new ComputeCatalogService(data,new PasswordUtils()),business,null,null,null);
        jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=2");
        var old=now().minusYears(1);var current=now();
        long deposit=historyPayment("DEPOSIT",2,"USDT","APPROVED",old);
        jdbc.update("UPDATE compute_deposit SET accounting_version=2,accounting_currency='USD',accounting_amount=10.25 WHERE id=?",deposit);
        long legacyDeposit=historyPayment("DEPOSIT",2,"TRX","APPROVED",old);
        jdbc.update("UPDATE compute_deposit SET wallet_type='PROJECT' WHERE id=?",legacyDeposit);
        historySnapshot("DEPOSIT",legacyDeposit,"5.5","5.5");
        historyPayment("DEPOSIT",2,"DOGE","APPROVED",old); // 缺旧快照，不按原币100或当前报价计入。
        long missingDeposit=historyPayment("DEPOSIT",2,"ETH","APPROVED",old);
        jdbc.update("UPDATE compute_deposit SET accounting_version=2,accounting_currency='USD' WHERE id=?",missingDeposit);
        historySnapshot("DEPOSIT",missingDeposit,"9999","9999"); // v2缺自身金额不能回退到旧快照。
        long invalidUnit=historyPayment("DEPOSIT",2,"EUR","APPROVED",old);
        jdbc.update("UPDATE compute_deposit SET accounting_version=2,accounting_currency='EUR',accounting_amount=8888 WHERE id=?",invalidUnit);
        historyPayment("DEPOSIT",2,"USDC","PENDING",current);

        long withdrawal=historyPayment("WITHDRAWAL",2,"BTC","APPROVED",old);
        jdbc.update("UPDATE compute_withdrawal SET accounting_version=2,accounting_currency='USD',accounting_amount=20,accounting_fee=1.25,accounting_net_amount=18.75 WHERE id=?",withdrawal);
        long legacyWithdrawal=historyPayment("WITHDRAWAL",2,"TRX","APPROVED",old);
        historySnapshot("WITHDRAWAL",legacyWithdrawal,"5","4.5");
        historyPayment("WITHDRAWAL",2,"DOGE","APPROVED",old);
        long missingWithdrawal=historyPayment("WITHDRAWAL",2,"ETH","APPROVED",old);
        jdbc.update("UPDATE compute_withdrawal SET accounting_version=2,accounting_currency='USD',accounting_amount=50 WHERE id=?",missingWithdrawal);
        historySnapshot("WITHDRAWAL",missingWithdrawal,"9999","9999");
        historyPayment("WITHDRAWAL",2,"USDC","REJECTED",current);
        historyPayment("WITHDRAWAL",2,"USDC","PROCESSING",current);
        historyPayment("WITHDRAWAL",2,"USDC","PROCESSING",current);
        historyPayment("WITHDRAWAL",4,"USDC","PROCESSING",current);
        historyPayment("WITHDRAWAL",3,"USDC","PROCESSING",current); // 当前管理员范围外。
        historyPayment("DEPOSIT",3,"USDT","APPROVED",old);
        login(2,"30200");
        var filters=Map.of("status","PROCESSING","currency","USDC","network","ERC20","startTime",current.minusHours(1).toInstant(java.time.ZoneOffset.UTC).toString());
        var page=rows(service.finances("withdrawals",filters));
        assertEquals(3,page.size());
        assertEquals(Set.of(2L,4L),new HashSet<>(page.stream().map(row->number(row.get("memberId"))).toList()));
        for(var row:page) {
            var totals=(Map<String,Object>)row.get("historyTotals");
            assertEquals("USD",totals.get("currency"));
            boolean child=number(row.get("memberId"))==2;
            assertEquals(0,new BigDecimal(child?"15.75":"0").compareTo(decimal(totals.get("totalDeposits"))));
            assertEquals(0,new BigDecimal(child?"23.25":"0").compareTo(decimal(totals.get("totalWithdrawals"))));
            assertEquals(child?3L:0L,number(totals.get("depositUnpricedCount")));
            assertEquals(child?2L:0L,number(totals.get("withdrawalUnpricedCount")));
            assertEquals(child,row.get("isTest"));
        }
        org.mockito.ArgumentCaptor<Map<String,Object>> capture=org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.times(1)).memberPaymentHistory(capture.capture());
        assertEquals(2,((List<?>)capture.getValue().get("ids")).size(),"同一用户多条提款只参加一次批量查询");
        assertEquals(Set.of(2L,4L),new HashSet<>((List<Long>)capture.getValue().get("ids")));
        var firstTotals=page.stream().filter(row->number(row.get("memberId"))==2).map(row->row.get("historyTotals")).toList();
        assertNotSame(firstTotals.get(0),firstTotals.get(1));
        assertTrue(rows(service.finances("withdrawals",Map.of("status","PROCESSING","currency","MISSING"))).isEmpty());
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.times(1)).memberPaymentHistory(org.mockito.ArgumentMatchers.anyMap());
        assertTrue(repository.memberPaymentHistory(List.of(3L),scope.queryScope()).isEmpty(),"批量历史查询自身仍复核代理范围");
    }

    private long historyPayment(String kind,long member,String currency,String status,java.time.LocalDateTime at) {
        var values=fields("member_id",member,"currency",currency,"network","USDC".equals(currency)?"ERC20":"NATIVE",
                "amount","100","status",status,"created_at",at,"reviewed_at","APPROVED".equals(status)?at:null);
        if("DEPOSIT".equals(kind))values.put("wallet_type","COMPUTE");
        else values.putAll(fields("fee","7","net_amount","93","address","fixture-recipient","rule_snapshot","{}"));
        return data.insert("DEPOSIT".equals(kind)?"compute_deposit":"compute_withdrawal",values);
    }

    private void historySnapshot(String kind,long id,String amount,String net) {
        jdbc.update("INSERT INTO compute_finance_snapshot(business_type,business_id,member_id,currency,native_amount,native_fee,native_net_amount,usd_rate,usd_amount,usd_fee,usd_net_amount,rate_provider,snapshot_source,posted_at,captured_at) VALUES(?,?,2,'TRX',100,0,100,0.05,?,0,?,'FIXTURE','LEGACY_BASELINE',?,?)",
                kind,id,new BigDecimal(amount),new BigDecimal(net),now().minusYears(1),now());
    }

    @Test void usernameManualFundsPickForeignAddressAndFilterByIndependentNumbers(){
        jdbc.update("UPDATE compute_currency SET fee_mode='CHANNEL',withdraw_fee_fixed=1");
        var input=fields("username","child","walletType","COMPUTE","amount","10.00000001","remark","人工凭证","requestId","v6-deposit-one");
        var deposit=tx.execute(status->service.manualDeposit(input));assertEquals("USDT",deposit.get("currency"));assertEquals("TRC20",deposit.get("network"));
        assertTrue(text(deposit.get("orderNo")).matches("DP[0-9A-HJKMNP-TV-Z]{16}"));
        tx.executeWithoutResult(status->service.reviewDeposit(number(deposit.get("id")),true,"通过",1));
        assertEquals(1L,number(service.finances("deposits",Map.of("username","child","orderNo",text(deposit.get("orderNo")),"firstDeposit","true")).get("total")));
        assertEquals(0L,number(service.finances("deposits",Map.of("firstDeposit","false")).get("total")));
        var ledger=rows(service.finances("ledger",Map.of("username","child","direction","IN"))).get(0);assertTrue(text(ledger.get("orderNo")).matches("LG[0-9A-HJKMNP-TV-Z]{16}"));
        assertNotEquals(deposit.get("orderNo"),ledger.get("orderNo"));
        assertEquals("server.compute.v6.withdrawalAddressRequired",assertThrows(BusinessException.class,()->service.manualWithdrawalQuote(fields("username","child","amount","20"))).getMessage());
        tx.executeWithoutResult(status->service.updateMember(2,Map.of("payPassword","Pay123456")));
        tx.executeWithoutResult(status->service.saveAddress(null,fields("username","child","currency","USDC","network","ERC20","label","外币收款","address","0x"+"2".repeat(40),"payPassword","Pay123456")));
        tx.executeWithoutResult(status->business.wallets(2,"USDC"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet WHERE member_id=2 AND currency='USDC'",Integer.class));
        jdbc.update("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen,updated_at) VALUES(2,'EARNINGS','USDC',7,0,?)",now());
        jdbc.update("UPDATE compute_wallet SET available=100 WHERE member_id=2 AND currency='USDT' AND wallet_type='EARNINGS'");
        var quote=service.manualWithdrawalQuote(fields("username","child","amount","20"));assertEquals("USDC",quote.get("currency"));assertEquals("1.00000000",quote.get("fee"));
        var request=fields("username","child","amount","20","remark","人工提款","requestId","v6-withdraw-one","expectedFee",quote.get("fee"),"expectedNetAmount",quote.get("netAmount"));
        for(String field:List.of("currency","network","addressId","address","memo","fields"))request.put(field,quote.get(field));
        var withdrawal=tx.execute(status->service.manualWithdrawal(request));assertEquals("USDC",withdrawal.get("currency"));assertEquals(data.json(withdrawal),data.json(tx.execute(status->service.manualWithdrawal(request))));
        assertEquals(0,new BigDecimal("20").compareTo(jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=2 AND currency='USDT' AND wallet_type='EARNINGS'",BigDecimal.class)));
        assertEquals(0,new BigDecimal("80").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=2 AND currency='USDT' AND wallet_type='EARNINGS'",BigDecimal.class)));
        assertEquals(0,new BigDecimal("7").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=2 AND currency='USDC' AND wallet_type='EARNINGS'",BigDecimal.class)));
        assertEquals(0,jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=2 AND currency='USDC' AND wallet_type='EARNINGS'",BigDecimal.class).signum());
        login(2,"30101");input.put("username","bravo");input.put("requestId","v6-outside-deposit");assertThrows(BusinessException.class,()->tx.execute(status->service.manualDeposit(input)));
    }

    @Test void reportAllIncludesDeepDescendantsAndCumulativeOptionsRemainBounded(){
        add(5,"thirdlevel",4L);add(6,"fourthlevel",5L);
        assertEquals(5L,number(rows(service.teamReport(Map.of("username","alpha"))).get(0).get("memberCount")));
        assertEquals(5L,number(rows(service.teamReport(Map.of("username","alpha","relationDepth","ALL"))).get(0).get("memberCount")));
        assertEquals(2L,number(rows(service.teamReport(Map.of("username","alpha","relationDepth","1"))).get(0).get("memberCount")));
        assertEquals(3L,number(rows(service.teamReport(Map.of("username","alpha","relationDepth","2"))).get(0).get("memberCount")));
        assertEquals(3L,number(rows(service.teamReport(Map.of("username","alpha","relationDepth","3","includeSelf","false"))).get(0).get("memberCount")));
        assertThrows(BusinessException.class,()->service.teamReport(Map.of("relationDepth","4")));
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('teamSupportedDepth','4')");
        assertEquals(5L,number(rows(service.teamReport(Map.of("username","alpha","relationDepth","4"))).get(0).get("memberCount")));
        assertThrows(BusinessException.class,()->service.teamReport(Map.of("relationDepth","5")));
        assertEquals(List.of(6L,5L,4L,2L),ids(service.members(Map.of("teamUsername","child"))));
        assertEquals(List.of(6L,5L,4L,2L),ids(service.teamReport(Map.of("teamUsername","child"))));
        var preview=service.previewInviter(2,Map.of("targetUsername","bravo"));assertEquals(3L,number(((Map<?,?>)preview.get("targetMember")).get("id")));
        tx.executeWithoutResult(status->service.transferInviter(2,fields("targetUsername","bravo","expectedVersion",preview.get("version"),"requestId","v6-username-migration","remark","迁移用户")));
        assertEquals(3L,jdbc.queryForObject("SELECT inviter_id FROM compute_member WHERE id=2",Long.class));
    }

    @Test void personalAndDashboardIncludeAutoButNeverMixCurrenciesOrTestMoney(){
        for(String currency:List.of("USDT","USDC")) {
            var deposit=tx.execute(status->service.manualDeposit(fields("username","child","walletType","COMPUTE","currency",currency,"network",currency.equals("USDT")?"TRC20":"ERC20","amount",currency.equals("USDT")?"20":"900","requestId","v6-personal-"+currency,"remark","统计")));
            tx.executeWithoutResult(status->service.reviewDeposit(number(deposit.get("id")),true,"通过",1));
        }
        data.insert("compute_deposit",fields("member_id",2,"wallet_type","COMPUTE","currency","USDC","network","ERC20",
                "amount",new BigDecimal("7"),"status","APPROVED","created_at",now(),"reviewed_at",now()));
        jdbc.update("UPDATE compute_deposit SET reviewed_at='2026-09-10 17:00:00'");
        long contract=data.insert("compute_auto_contract",fields("member_id",2,"level_id",1,"level_name","基础","request_id","auto-report","currency","USDT","amount",new BigDecimal("10"),"daily_rate",BigDecimal.ONE,"status","COMPLETED","rule_snapshot","{}","expected_income",new BigDecimal("2"),"paid_income",new BigDecimal("2"),"starts_at",now(),"created_at",java.time.LocalDateTime.parse("2026-09-10T17:00:00")));
        data.insert("compute_auto_settlement",fields("contract_id",contract,"member_id",2,"settlement_day",1,"settlement_date",java.time.LocalDate.now(java.time.ZoneOffset.UTC),"income",new BigDecimal("2"),"principal",BigDecimal.ZERO,"created_at",now()));
        tx.executeWithoutResult(status->business.changeWallet(2,"EARNINGS","USDT",new BigDecimal("2"),BigDecimal.ZERO,"AUTO_INCOME","auto-report-income","AC-test"));
        jdbc.update("UPDATE compute_ledger SET created_at='2026-09-10 17:00:00' WHERE kind='AUTO_INCOME'");
        var report=service.personalReport(Map.of("username","child","memberId","2","currency","USDT","startTime","2026-09-10T16:00:00Z","endTime","2026-09-11T15:59:59Z"));
        var person=rows(report).get(0);assertEquals(2L,number(person.get("id")));assertFalse(person.containsKey("totalDeposits"));assertEquals(0,new BigDecimal("920").compareTo(decimal(currency(person,"USDT").get("totalDeposits"))));assertEquals(0,new BigDecimal("2").compareTo(decimal(currency(person,"USDT").get("autoIncome"))));
        assertEquals(0,new BigDecimal("7").compareTo(decimal(currency(person,"USDC").get("totalDeposits"))));assertEquals(3L,number(person.get("depositCount")));assertEquals(1L,number(person.get("depositMembers")));
        assertEquals(1L,number(person.get("directCount")));assertEquals(2L,number(person.get("teamCount")));
        assertEquals(2,((List<?>)person.get("currencySummaries")).size());assertEquals("Asia/Shanghai",report.get("timeZone"));
        var trends=(List<Map<String,Object>>)report.get("trendByCurrency");assertEquals(2,trends.size());
        var trend=(Map<?,?>)((List<?>)trends.stream().filter(item->"USDT".equals(item.get("currency"))).findFirst().orElseThrow().get("trend")).get(0);assertEquals("2026-09-11",trend.get("day"));assertEquals(0,new BigDecimal("920").compareTo(decimal(trend.get("totalDeposits"))));
        var usdcTrend=(Map<?,?>)((List<?>)trends.stream().filter(item->"USDC".equals(item.get("currency"))).findFirst().orElseThrow().get("trend")).get(0);assertEquals(0,new BigDecimal("7").compareTo(decimal(usdcTrend.get("totalDeposits"))));
        var dashboard=business.dashboard(2);assertEquals(0,decimal(dashboard.get("activePrincipal")).signum());assertEquals(0,new BigDecimal("2").compareTo(decimal(dashboard.get("todayIncome"))));assertEquals(7,((List<?>)dashboard.get("incomeTrend")).size());
        assertEquals(0,decimal(currency(service.overview(Map.of()),"USDT").get("activePrincipal")).signum());
        tx.executeWithoutResult(status->service.updateMember(2,Map.of("isTest",true)));
        assertEquals(0,decimal(currency(service.overview(Map.of()),"USDT").get("activePrincipal")).signum());assertEquals(0,decimal(currency(rows(service.personalReport(Map.of("username","child","memberId","2"))).get(0),"USDT").get("autoIncome")).signum());
        assertEquals(0,new BigDecimal("2").compareTo(decimal(business.dashboard(2).get("totalIncome"))));
    }

    @Test void nonStableCoinReportsUseFixedUsdAndKeepLegacyNativeBucket(){
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled,fee_mode,withdraw_fee_fixed,withdraw_fee_rate) VALUES('TRX','TRC20',1,'CHANNEL',1,0)");
        TokenTestSchema.seedCurrencies(jdbc);
        jdbc.update("INSERT INTO compute_exchange_rate(rate_type,currency,rate,provider,source_id,source_at,refreshed_at) VALUES('ASSET','TRX',0.1,'MCAG','TRX',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var deposit=tx.execute(status->service.manualDeposit(fields("memberId",2,"walletType","COMPUTE","currency","TRX","network","TRC20",
                "amount","100","requestId","usd-report-deposit","remark","美元报表")));
        tx.executeWithoutResult(status->service.reviewDeposit(number(deposit.get("id")),true,"确认",1));
        data.insert("compute_deposit",fields("member_id",2,"wallet_type","COMPUTE","currency","TRX","network","TRC20",
                "amount",new BigDecimal("3"),"status","APPROVED","created_at",now(),"reviewed_at",now()));
        jdbc.update("UPDATE compute_wallet SET available=5 WHERE member_id=2 AND wallet_type='EARNINGS' AND currency='USDT'");
        var input=fields("memberId",2,"currency","TRX","network","TRC20","address","T"+"1".repeat(33),"amount","20");
        var quote=service.manualWithdrawalQuote(input);
        input.putAll(fields("requestId","usd-report-withdrawal","remark","美元报表","expectedFee",quote.get("fee"),"expectedNetAmount",quote.get("netAmount")));
        var withdrawal=tx.execute(status->service.manualWithdrawal(input));
        tx.executeWithoutResult(status->service.reviewWithdrawal(number(withdrawal.get("id")),true,"确认",1));
        jdbc.update("UPDATE compute_exchange_rate SET rate=9 WHERE currency='TRX'");
        var personalReport=service.personalReport(Map.of("memberId","2"));
        for(var report:List.of(rows(service.teamReport(Map.of("memberId","1"))).get(0),rows(personalReport).get(0),service.overview(Map.of()))) {
            var usd=currency(report,"USDT");
            assertEquals(0,new BigDecimal("10").compareTo(decimal(usd.get("totalDeposits"))));
            assertEquals(0,new BigDecimal("1.9").compareTo(decimal(usd.get("totalWithdrawals"))));
            assertEquals(0,new BigDecimal("3").compareTo(decimal(currency(report,"TRX").get("totalDeposits"))));
        }
        var person=rows(service.members(Map.of("memberId","2"))).get(0);
        assertEquals(0,new BigDecimal("10").compareTo(decimal(person.get("totalDeposits"))));
        assertEquals(0,new BigDecimal("1.9").compareTo(decimal(person.get("totalWithdrawals"))));
        var summaries=(List<Map<String,Object>>)personalReport.get("trendByCurrency");
        var usdTrend=(List<Map<String,Object>>)summaries.stream().filter(row->"USDT".equals(row.get("currency"))).findFirst().orElseThrow().get("trend");
        assertEquals(0,new BigDecimal("10").compareTo(usdTrend.stream().map(row->decimal(row.get("totalDeposits"))).reduce(BigDecimal.ZERO,BigDecimal::add)));
        assertEquals(0,new BigDecimal("1.9").compareTo(usdTrend.stream().map(row->decimal(row.get("totalWithdrawals"))).reduce(BigDecimal.ZERO,BigDecimal::add)));
        var fixed=rows(service.finances("deposits",Map.of("orderNo",text(deposit.get("orderNo"))))).get(0);
        assertEquals("TRX",fixed.get("currency"));assertEquals(0,new BigDecimal("100").compareTo(decimal(fixed.get("amount"))));
        assertEquals("USD",fixed.get("accountingCurrency"));assertEquals(0,new BigDecimal("10").compareTo(decimal(fixed.get("accountingAmount"))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet WHERE member_id=2 AND currency='TRX'",Integer.class));
    }

    @Test void vipCalculationBasisNeverBecomesPrincipalAndPostedIncomeStaysSeparateAcrossReports(){
        jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=4");
        data.insert("compute_order",fields("member_id",2,"product_id",1,"product_name","独立GPU产品","product_type","GPU","source_wallet","PROJECT","currency","USDT",
                "amount",new BigDecimal("80"),"daily_rate",new BigDecimal("0.1"),"duration_days",30,"settlement_mode","A","product_snapshot","{}",
                "expected_income",new BigDecimal("5"),"paid_income",new BigDecimal("5"),"settled_days",0,"status","ACTIVE","starts_at",now(),"matures_at",now().plusDays(30),"created_at",now()));
        String[] incomes={"0","2","0","3","3"};
        for(int index=0;index<incomes.length;index++) {
            data.insert("compute_auto_contract",fields("member_id",2,"level_id",1,"level_name","基础","request_id","current-report-"+index,"currency","USDT","amount",new BigDecimal("1000"),
                    "daily_rate",BigDecimal.ONE,"rule_snapshot","{}","expected_income",new BigDecimal(incomes[index]),"paid_income",new BigDecimal(incomes[index]),
                    "status","COMPLETED","starts_at",now(),"created_at",now()));
        }
        data.insert("compute_auto_contract",fields("member_id",4,"level_id",1,"level_name","基础","request_id","current-report-test","currency","USDT","amount",new BigDecimal("500"),
                "daily_rate",BigDecimal.ONE,"rule_snapshot","{}","expected_income",BigDecimal.TEN,"paid_income",BigDecimal.TEN,
                "status","COMPLETED","starts_at",now(),"created_at",now()));
        jdbc.update("UPDATE compute_wallet SET available=500,frozen=1000 WHERE member_id=2 AND currency='USDT' AND wallet_type='COMPUTE'");
        tx.executeWithoutResult(status->business.changeWallet(2,"EARNINGS","USDT",new BigDecimal("8"),BigDecimal.ZERO,"AUTO_INCOME","v8-report-auto-income","AC-report"));
        tx.executeWithoutResult(status->business.changeWallet(2,"EARNINGS","USDT",new BigDecimal("5"),BigDecimal.ZERO,"ORDER_INCOME","v8-report-product-income","PO-report"));
        tx.executeWithoutResult(status->business.changeWallet(4,"EARNINGS","USDT",BigDecimal.TEN,BigDecimal.ZERO,"AUTO_INCOME","v8-report-test-income","AC-test-report"));
        var member=rows(service.members(Map.of("memberId","2"))).get(0);
        assertEquals(0,new BigDecimal("80").compareTo(decimal(member.get("activePrincipal"))));
        assertEquals(0,new BigDecimal("1000").compareTo(decimal(member.get("frozen"))));
        assertEquals(0,new BigDecimal("13").compareTo(decimal(member.get("totalIncome"))));
        assertEquals(1L,number(member.get("orderCount")));assertEquals(1L,number(member.get("activeOrders")));
        var team=rows(service.teamReport(Map.of("memberId","1"))).get(0);
        assertEquals(3L,number(team.get("memberCount")));assertFalse(team.containsKey("totalBalance"));
        var personal=rows(service.personalReport(Map.of("memberId","2"))).get(0);
        var overview=service.overview(Map.of());
        for(var report:List.of(personal,overview)){
            var values=currency(report,"USDT");
            assertEquals(0,new BigDecimal("80").compareTo(decimal(values.get("activePrincipal"))));
            assertEquals(0,BigDecimal.ZERO.compareTo(decimal(values.get("autoAmount"))));
            assertEquals(0,new BigDecimal("8").compareTo(decimal(values.get("autoIncome"))));
        }
        // 测试用户只有金额排除；团队/概览仍包含其一笔执行，个人报表只含本人五笔。
        assertFalse(currency(team,"USDT").containsKey("autoCount"));assertFalse(currency(team,"USDT").containsKey("activePrincipal"));assertFalse(currency(team,"USDT").containsKey("totalIncome"));
        assertEquals(5L,number(currency(personal,"USDT").get("autoCount")));
        assertEquals(6L,number(currency(overview,"USDT").get("autoCount")));
        assertEquals(1L,number(overview.get("activeOrders")));assertEquals(0L,number(overview.get("activeAuto")));
        var oldWindow=rows(service.teamReport(Map.of("memberId","1","endTime","2020-01-01T00:00:00Z"))).get(0);
        assertFalse(currency(oldWindow,"USDT").containsKey("activePrincipal"));
        assertFalse(currency(oldWindow,"USDT").containsKey("autoAmount"));assertFalse(oldWindow.containsKey("autoCount"));
        var testMember=rows(service.members(Map.of("memberId","4"))).get(0);assertEquals(0,BigDecimal.ZERO.compareTo(decimal(testMember.get("activePrincipal"))));
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> currency(Map<String,Object> row,String code) {
        return ((List<Map<String,Object>>)row.get("currencySummaries")).stream().filter(item->code.equals(item.get("currency"))).findFirst().orElse(Map.of());
    }

    @Test void financialResponsesResolveMemberAndAdministratorNames(){
        var request=fields("username","child","walletType","COMPUTE","amount","20","requestId","v7-names-deposit","remark","人工录入");
        var deposit=tx.execute(status->service.manualDeposit(request));
        assertEquals("child",deposit.get("username"));assertEquals("admin",deposit.get("createdByUsername"));
        assertNull(deposit.get("reviewedByUsername"));
        var reviewed=tx.execute(status->service.reviewDeposit(number(deposit.get("id")),true,"确认",3));
        assertEquals("global",reviewed.get("reviewedByUsername"));
        var listed=rows(service.finances("deposits",Map.of())).get(0);
        assertEquals("child",listed.get("username"));assertEquals("global",listed.get("reviewedByUsername"));assertEquals("admin",listed.get("createdByUsername"));
        var ledger=rows(service.finances("ledger",Map.of("username","child"))).get(0);assertEquals("global",ledger.get("operatorUsername"));
        assertEquals("child",service.memberWallets(2).get("username"));
        jdbc.update("DELETE FROM ad_master WHERE uid=3");
        assertNull(rows(service.finances("deposits",Map.of())).get(0).get("reviewedByUsername"));
        login(2,"30100");assertEquals(1L,number(service.finances("deposits",Map.of()).get("total")));
        assertTrue(rows(service.finances("deposits",Map.of("username","bravo"))).isEmpty());
    }

    @Test void gpuProductsDoNotRequireModelsAndSettlementKeepsOrderSnapshot(){
        long provider=data.insert("compute_provider",fields("code","sample","name","供应方","enabled",false));
        long model=data.insert("compute_model",fields("provider_id",provider,"name","模型","input_price",BigDecimal.ZERO,"output_price",BigDecimal.ZERO,"updated_at",now()));
        long product=data.insert("compute_product",fields("name","日息产品","type","GPU","currency","USDT","min_amount",BigDecimal.ONE,"max_amount",new BigDecimal("1000"),"duration_days",2,"daily_rate_min",new BigDecimal("0.1"),"daily_rate_max",new BigDecimal("0.1"),"settlement_mode","A","updated_at",now()));
        jdbc.update("UPDATE compute_wallet SET available=100 WHERE member_id=2 AND currency='USDT' AND wallet_type='PROJECT'");
        var request=fields("requestId","v6-provider-purchase","productId",product,"amount","50");
        var quote=tx.execute(status->new ComputeProductQuoteService(data).quote(2,request));request.put("quoteId",quote.get("quoteId"));
        var order=tx.execute(status->business.buyOrder(2,request));assertEquals(0,new BigDecimal("0.1").compareTo(decimal(order.get("expectedIncome"))));
        assertFalse(order.containsKey("modelIds"));
        assertEquals(1L,number(service.finances("orders",Map.of("productId",String.valueOf(product),"orderNo",text(order.get("orderNo")))).get("total")));
        jdbc.update("UPDATE compute_provider SET enabled=0 WHERE id=?",provider);
        tx.executeWithoutResult(status->business.settleOrder(number(order.get("id")),now().plusDays(3)));
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM compute_order WHERE id=?",String.class,order.get("id")));
        assertEquals(0,new BigDecimal("50.1").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=2 AND currency='USDT' AND wallet_type='EARNINGS'",BigDecimal.class)));
    }
}
