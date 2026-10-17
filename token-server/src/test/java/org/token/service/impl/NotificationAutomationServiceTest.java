package org.token.service.impl;

import org.token.config.compute.ComputeMcagProperties;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeAgentScopeRepository;
import org.token.repository.support.NotificationFixture;
import org.token.repository.system.NotificationTemplateRepository;
import org.token.model.entity.NotificationTemplate;
import org.token.service.NotificationService;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeMcagClient;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationAutomationServiceTest {
    private NotificationFixture f;
    private ComputeAgentScope scope;
    private NotificationTemplateRenderer renderer;
    private NotificationService notifications;
    private NotificationTemplateRepository templates;
    private TelegramNotificationService telegram;
    private ComputeMcagClient client;
    private NotificationAutomationService service;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        f=new NotificationFixture();scope=mock(ComputeAgentScope.class);renderer=mock(NotificationTemplateRenderer.class);notifications=mock(NotificationService.class);
        templates=mock(NotificationTemplateRepository.class);telegram=mock(TelegramNotificationService.class);client=mock(ComputeMcagClient.class);
        var provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(client);
        var config=new ComputeMcagProperties();config.setEnabled(true);
        when(scope.isGlobalScope()).thenReturn(true);when(scope.currentAdminUid()).thenReturn(1);
        when(templates.findAllOrdered()).thenReturn(List.of());
        when(renderer.render(anyString(),anyString(),anyMap())).thenAnswer(call->new NotificationTemplateRenderer.Rendered("title","body",call.<String>getArgument(0).endsWith("TG")?"TELEGRAM":"INTERNAL"));
        when(renderer.enabled(anyString())).thenAnswer(call->{var template=new NotificationTemplate();template.setCode(call.getArgument(0));template.setChannelType(call.<String>getArgument(0).endsWith("TG")?"TELEGRAM":"INTERNAL");return template;});
        service=new NotificationAutomationService(f.repository,f.data,scope,new ComputeAgentScopeRepository(f.jdbc),templates,renderer,notifications,telegram,provider,config,f.manager);
        f.jdbc.update("INSERT INTO compute_member(id,username,inviter_id) VALUES(1,'first',NULL),(2,'second',NULL),(3,'child',1)");
        f.jdbc.update("INSERT INTO user_role VALUES(1,'30100,30200',0),(2,'30100,30200',0),(3,'60101',0),(4,'80203',0)");
        f.jdbc.update("INSERT INTO ad_master(uid,locale,role,state) VALUES(1,'en',1,1),(2,'en',2,1),(3,'en',3,1),(4,'en',4,1)");
        f.jdbc.update("INSERT INTO compute_admin_member_scope VALUES(1,1),(2,2)");
    }
    @Test void defaultsStayDisabledAndEnableTimeIsServerOwnedAndPreserved() {
        var rules=rules();assertTrue(rules.values().stream().noneMatch(rule->Boolean.TRUE.equals(rule.get("enabled"))));
        rules.get("largeDeposit").put("enabled",true);rules.get("largeDeposit").put("enabledAt","2000-01-01T00:00:00Z");
        var result=service.save(Map.of("version",0,"rules",rules));assertEquals(1L,result.get("version"));
        var stored=f.data.jsonMap(f.repository.configuration(false).get("configJson"));
        String since=String.valueOf(((Map<?,?>)stored.get("largeDeposit")).get("enabledAt"));
        assertTrue(Instant.parse(since).isAfter(Instant.now().minusSeconds(30)));
        service.save(Map.of("version",1,"rules",rules));
        var again=f.data.jsonMap(f.repository.configuration(false).get("configJson"));assertEquals(since,((Map<?,?>)again.get("largeDeposit")).get("enabledAt"));
        assertThrows(BusinessException.class,()->service.save(Map.of("version",1,"rules",rules)));
        assertFalse(((Map<?,?>)((Map<?,?>)result.get("rules")).get("largeDeposit")).containsKey("enabledAt"));
    }
    @Test void scopedSettingsRevealNoRulesTemplatesOrBindingData(){
        when(scope.isGlobalScope()).thenReturn(false);
        assertEquals(Map.of("global",false,"rules",Map.of(),"templates",List.of()),service.settings());
        verifyNoInteractions(templates,telegram);
    }
    @Test void fixedUsdAndSavedLegacySnapshotsRespectEnableBoundaryScopeAndPersistentDedup(){
        enable("largeDeposit");
        deposit(1,1,"APPROVED",2,"USD",1000,"CURRENT_TIMESTAMP","DATEADD('DAY',-3,CURRENT_TIMESTAMP)");
        deposit(2,3,"APPROVED",2,"USD",1000,"CURRENT_TIMESTAMP","CURRENT_TIMESTAMP");
        deposit(3,3,"APPROVED",2,"BTC",1000,"CURRENT_TIMESTAMP","CURRENT_TIMESTAMP");
        deposit(4,3,"APPROVED",1,"",999999,"CURRENT_TIMESTAMP","CURRENT_TIMESTAMP");
        f.jdbc.update("INSERT INTO compute_finance_snapshot VALUES('DEPOSIT',4,2000)");
        deposit(5,3,"PENDING",2,"USD",1000,"CURRENT_TIMESTAMP","CURRENT_TIMESTAMP");
        assertEquals(List.of(2L,4L),candidates("largeDeposit").stream().map(row->((Number)row.get("id")).longValue()).toList());
        service.scan();service.scan();
        verify(notifications,times(2)).sendPopup(eq(1),eq("title"),eq("body"),eq("WARNING"));
        verify(notifications,never()).sendPopup(eq(2),any(),any(),any());verify(notifications,never()).sendPopup(eq(3),any(),any(),any());
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM notification_event_delivery WHERE target_key='EVENT'",Integer.class));
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM notification_event_delivery WHERE source_key IN ('1','3','5')",Integer.class));
        verifyNoInteractions(client,telegram);
    }
    @Test void ignoredApprovedAndNewWithdrawalsDoNotTriggerOverdueAlert(){
        enable("withdrawalOverdue");
        for(int id=1;id<=5;id++)f.jdbc.update("INSERT INTO compute_withdrawal(id,member_id,order_no,status,created_at,attention_ignored) VALUES(?,1,?,'PENDING',DATEADD('MINUTE',-45,CURRENT_TIMESTAMP),0)",id,"W"+id);
        f.jdbc.update("UPDATE compute_withdrawal SET attention_ignored=1 WHERE id=2");
        f.jdbc.update("UPDATE compute_withdrawal SET status='APPROVED' WHERE id=3");
        f.jdbc.update("UPDATE compute_withdrawal SET created_at=CURRENT_TIMESTAMP WHERE id=4");
        f.jdbc.update("UPDATE compute_withdrawal SET created_at=DATEADD('DAY',-3,CURRENT_TIMESTAMP) WHERE id=5");
        assertEquals(List.of(1L),candidates("withdrawalOverdue").stream().map(row->((Number)row.get("id")).longValue()).toList());
        service.scan();service.scan();
        verify(notifications,times(1)).sendPopup(eq(1),any(),any(),any());
        assertEquals(List.of("1"),f.jdbc.queryForList("SELECT source_key FROM notification_event_delivery WHERE target_key='EVENT'",String.class));
    }
    @Test void disabledTelegramTargetIsSkippedWithoutBlockingPopupsOrLaterOrders(){
        enable("largeDeposit");
        var config=f.data.jsonMap(f.repository.configuration(false).get("configJson"));
        @SuppressWarnings("unchecked") var rule=(Map<String,Object>)config.get("largeDeposit");rule.put("channelId",7L);
        f.configuration(config);
        when(telegram.validateTargets(eq(7L),eq("ALL"),eq(List.of()))).thenThrow(new BusinessException("server.notification.channel.disabled"));
        deposit(1,1,"APPROVED",2,"USD",1000,"CURRENT_TIMESTAMP","CURRENT_TIMESTAMP");deposit(2,1,"APPROVED",2,"USD",2000,"CURRENT_TIMESTAMP","CURRENT_TIMESTAMP");
        service.scan();service.scan();
        verify(notifications,times(2)).sendPopup(eq(1),any(),any(),any());
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM notification_event_delivery WHERE target_key='TG_CHANNEL:7' AND status='SKIPPED'",Integer.class));
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM notification_event_delivery WHERE target_key='EVENT'",Integer.class));
        verify(telegram,never()).sendBound(any(),any(),any(),any(),any(),any());
    }
    @Test void currentPermissionAndActiveRootsPreventCrossAgentAndEnergyDisclosure(){
        Map<String,Object> first=f.repository.administrator(1);
        assertTrue(service.authorized(first,"largeDeposit",3L));assertFalse(service.authorized(first,"largeDeposit",2L));
        f.jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=1");assertFalse(service.authorized(first,"largeDeposit",3L));
        assertFalse(service.authorized(f.repository.administrator(3),"largeDeposit",1L));
        assertTrue(service.authorized(f.repository.administrator(4),"energyLow",null));
        f.jdbc.update("INSERT INTO compute_admin_member_scope VALUES(4,2)");assertFalse(service.authorized(f.repository.administrator(4),"energyLow",null));
        assertFalse(service.authorized(Map.of("uid",9,"permissions","super","isSuper",0),"largeDeposit",1L));
    }
    @Test void ignoreOrConfigurationChangeAfterCandidateSelectionCancelsDelivery(){
        enable("withdrawalOverdue");
        f.jdbc.update("INSERT INTO compute_withdrawal(id,member_id,order_no,status,created_at) VALUES(1,1,'W1','PENDING',DATEADD('MINUTE',-45,CURRENT_TIMESTAMP))");
        assertEquals(1,candidates("withdrawalOverdue").size());
        var repository=spy(f.repository);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"repository",repository);
        doAnswer(call->{var rows=call.callRealMethod();f.jdbc.update("UPDATE compute_withdrawal SET attention_ignored=1 WHERE id=1");return rows;})
                .when(repository).candidates(eq("withdrawalOverdue"),any(),any(),any());
        service.scan();verifyNoInteractions(notifications);
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM notification_event_delivery",Integer.class));
        f.jdbc.update("UPDATE compute_withdrawal SET attention_ignored=0 WHERE id=1");
        doAnswer(call->{var rows=call.callRealMethod();f.jdbc.update("UPDATE system_setting SET revision=revision+1 WHERE setting_key='notification.automation.configuration'");return rows;})
                .when(repository).candidates(eq("withdrawalOverdue"),any(),any(),any());
        service.scan();verifyNoInteractions(notifications);
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM notification_event_delivery",Integer.class));
    }
    @Test void unavailableEnergyIsNotZeroAndContinuousLowBalanceAlertsOnlyOnceUntilRecovery(){
        enable("energyLow");
        when(client.request(eq("energy-account/query"),eq(Map.of()))).thenReturn(energy("STALE","1"),energy("READY","1"),energy("READY","1"),energy("READY","200"),energy("READY","1"));
        for(int i=0;i<5;i++)service.scan();
        verify(notifications,times(2)).sendPopup(eq(4),eq("title"),eq("body"),eq("WARNING"));
        verify(notifications,never()).sendPopup(eq(1),any(),any(),any());
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM notification_event_delivery WHERE event_code='energyLow' AND target_key='EVENT'",Integer.class));
        var independent=new LinkedHashMap<>(energy("READY","1"));independent.put("mode","INDEPENDENT");independent.put("name","Account B");independent.put("recharge_address","other-public-address");
        when(client.request(eq("energy-account/query"),eq(Map.of()))).thenReturn(independent);
        service.scan();service.scan();
        verify(notifications,times(3)).sendPopup(eq(4),eq("title"),eq("body"),eq("WARNING"));
        String state=f.jdbc.queryForObject("SELECT source_key FROM notification_automation_state",String.class);
        assertFalse(state.contains("Account B"));assertFalse(state.contains("other-public-address"));
    }
    @Test void fractionalMinutesAndInvalidTargetsAreRejectedBeforeConfigWrite(){
        var first=rules();first.get("withdrawalOverdue").put("minutes","1.1");
        assertThrows(BusinessException.class,()->service.save(Map.of("version",0,"rules",first)));
        assertEquals(0L,f.jdbc.queryForObject("SELECT revision FROM system_setting WHERE setting_key='notification.automation.configuration'",Long.class));
        var invalid=rules();invalid.get("largeDeposit").put("severity","html");
        assertThrows(BusinessException.class,()->service.save(Map.of("version",0,"rules",invalid)));
    }
    @Test void exactBigintSourceKeysKeepNonNumericEventsSeparateAndExcludeOnlyCompletedOrders() {
        enable("largeDeposit");
        long first=9007199254740992L,second=9007199254740993L;
        deposit(first,1,"APPROVED",2,"USD",1000,"CURRENT_TIMESTAMP","CURRENT_TIMESTAMP");
        deposit(second,1,"APPROVED",2,"USD",1000,"CURRENT_TIMESTAMP","CURRENT_TIMESTAMP");
        f.repository.claim("largeDeposit",String.valueOf(first),"EVENT",org.token.repository.compute.ComputeData.now());
        f.repository.claim("largeDeposit",second+":energy-identity","EVENT",org.token.repository.compute.ComputeData.now());
        assertEquals(List.of(second),candidates("largeDeposit").stream().map(row->((Number)row.get("id")).longValue()).toList());
        service.scan();service.scan();
        verify(notifications,times(1)).sendPopup(eq(1),any(),any(),any());
        assertTrue(candidates("largeDeposit").isEmpty());
    }
    @Test void moreThanOneBatchIsProcessedWithoutReplayingEarlierOrders() {
        enable("largeDeposit");
        for(int id=1;id<=105;id++)deposit(id,1,"APPROVED",2,"USD",1000,"CURRENT_TIMESTAMP","CURRENT_TIMESTAMP");
        service.scan();assertEquals(5,candidates("largeDeposit").size());
        service.scan();service.scan();
        verify(notifications,times(105)).sendPopup(eq(1),any(),any(),any());
        assertEquals(105,f.jdbc.queryForObject("SELECT COUNT(*) FROM notification_event_delivery WHERE target_key='EVENT'",Integer.class));
    }
    @Test void diagnosticDetailsKeepSqlAndExternalCredentialsOutOfLogs() {
        var sql=new java.sql.SQLException("secret SQL and parameters bot-token", "HY000",1267);
        var wrapped=new IllegalStateException("private wrapper content",sql);
        assertEquals("分类=DATABASE；SQLState=HY000；数据库码=1267；异常=SQLException",NotificationAutomationService.failureDetails(wrapped));
        assertEquals("分类=BUSINESS；异常=BusinessException；业务键=server.notification.channel.disabled",NotificationAutomationService.failureDetails(new BusinessException("server.notification.channel.disabled")));
        assertEquals("分类=RUNTIME；异常=IllegalStateException",NotificationAutomationService.failureDetails(new IllegalStateException("https://secret-token/credentials")));
    }
    private static Map<String,Object> energy(String status,String balance){return Map.of("status",status,"configured",true,"enabled",true,"balance_trx",balance,"provider_id","provider-a","mode","GLOBAL","name","Provider A","recharge_address","public-address");}
    private List<Map<String,Object>> candidates(String event){
        var now=org.token.repository.compute.ComputeData.now();
        boolean overdue="withdrawalOverdue".equals(event);
        var threshold=new java.math.BigDecimal(overdue?"0":"1000");
        var rows=f.repository.candidates(event,now.minusHours(2),now.minusMinutes(overdue?30:0),threshold);
        for(var row:rows)assertTrue(f.repository.eligible(event,((Number)row.get("id")).longValue(),now.minusHours(2),now.minusMinutes(overdue?30:0),threshold));
        return rows;
    }
    private void enable(String event){var rules=rules();rules.get(event).put("enabled",true);rules.get(event).put("enabledAt",Instant.now().minusSeconds(7200).toString());if(!event.equals("energyLow")&&!event.equals("withdrawalOverdue"))rules.get(event).put("threshold","1000");f.configuration(rules);}
    private static Map<String,Map<String,Object>> rules(){var rules=new LinkedHashMap<String,Map<String,Object>>();for(String event:List.of("largeDeposit","largeWithdrawal","withdrawalOverdue","energyLow"))rules.put(event,NotificationAutomationService.defaults(event));return rules;}
    private void deposit(long id,long member,String status,int version,String currency,int amount,String created,String reviewed){f.jdbc.update("INSERT INTO compute_deposit(id,member_id,order_no,status,accounting_version,accounting_currency,accounting_amount,created_at,reviewed_at) VALUES(?,?,?,?,?,?,?,"+created+","+reviewed+")",id,member,"D"+id,status,version,currency,amount);}
}
