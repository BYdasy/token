package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.config.EnabledLocaleResolver;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.model.request.compute.ComputeAuthRequests;
import org.token.service.compute.ComputeAgentScope;
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
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅在独立H2中验证注册触发、用户可见消息、幂等、模板英语与事务回滚。 */
@Tag("regression")
class ComputeSystemMessageIntegrationTest {
    JdbcTemplate jdbc;FixtureComputeData data;ComputeSystemMessageService messages;TransactionTemplate tx;
    ComputeAgentScope scope;ComputeContentService content;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:messages_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);data=new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules());
        var manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);
        TokenTestSchema.create(jdbc, "compute_member","system_setting","compute_member_session","compute_recovery_request","compute_currency","compute_wallet","compute_ledger","compute_request","compute_model","compute_model_price","compute_level","compute_product","compute_order","compute_order_settlement","compute_deposit","compute_fee_rule","compute_transfer_rule","compute_transfer","compute_withdrawal","compute_address","compute_job","compute_job_run","compute_content","compute_content_recipient","compute_system_message_template","compute_system_message_delivery");
        TokenTestSchema.apply(jdbc);
        jdbc.execute("CREATE TABLE translates(id INT AUTO_INCREMENT PRIMARY KEY,tkey VARCHAR(160),lang VARCHAR(20),tvalue VARCHAR(65535),type INT,prop INT,title VARCHAR(200),is_del INT,action_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE translate_language(lang VARCHAR(20),status INT)");
        jdbc.update("INSERT INTO translate_language VALUES('zh',1),('en',1),('fr',1)");
        jdbc.update("INSERT INTO compute_system_message_template VALUES('WELCOME',1,CURRENT_TIMESTAMP),('DEPOSIT_APPROVED',1,CURRENT_TIMESTAMP),('WITHDRAWAL_COMPLETED',1,CURRENT_TIMESTAMP)");
        messages=new ComputeSystemMessageService(data);
        for(String event:ComputeSystemMessageService.EVENTS)tx.executeWithoutResult(status->messages.save(event,Map.of("translations",Map.of(
                "zh",Map.of("title","中文标题","bodyHtml","<p>您好 {username}，{amount} {currency} {orderId}</p>"),
                "en",Map.of("title","Welcome","bodyHtml","<p>Hello {username}, {amount} {currency} {orderId}</p>")))));
        scope=mock(ComputeAgentScope.class);when(scope.relations()).thenReturn(mock(ComputeMemberRelations.class));
        when(scope.resolveInviter(null)).thenReturn(null);
        var locales=mock(EnabledLocaleResolver.class);when(locales.resolve(anyString())).thenAnswer(call->call.getArgument(0));
        when(locales.normalize(anyString())).thenAnswer(call->call.getArgument(0));
        content=new ComputeContentService(jdbc,data,scope,locales,Runnable::run,manager);
    }
    private long register(){var accounts=new ComputeAccountService(jdbc,new PasswordUtils(),mock(ComputeBusinessService.class),scope,messages);var response=tx.execute(status->accounts.register(new ComputeAuthRequests.Register("messageuser",null,null,"abcdefg",null)));return ((Number)((Map<?,?>)response.get("member")).get("id")).longValue();}

    @Test void registrationCreatesOneInboxMessageAndFallbackEnglishIsVisibleOnlyToRecipient(){
        long member=register();
        var result=content.userList(member,Map.of("kind","MESSAGE"),"fr");
        var row=(Map<?,?>)((List<?>)result.get("records")).get(0);
        assertEquals("Welcome",row.get("title"));assertEquals("en",row.get("translationLanguage"));
        assertTrue(row.get("bodyHtml").toString().contains("messageuser"));
        assertEquals(0L,content.userList(member+1,Map.of("kind","MESSAGE"),"en").get("total"));
        tx.executeWithoutResult(status->messages.welcome(member));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_recipient",Integer.class));
    }
    @Test void approvedDepositSnapshotsIndependentOrderNumberOnceAndRetainsDeliveredBody(){
        long member=register();
        long deposit=data.insert("compute_deposit",ComputeData.fields("member_id",member,"wallet_type","COMPUTE","currency","USDT","network","TRC20","amount",new BigDecimal("10.12345678"),"order_no","DP-EXAMPLE","created_at",java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
        tx.executeWithoutResult(status->{messages.depositApproved(deposit,member,new BigDecimal("10.12345678"),"USDT");messages.depositApproved(deposit,member,new BigDecimal("10.12345678"),"USDT");});
        long contentId=jdbc.queryForObject("SELECT content_id FROM compute_system_message_delivery WHERE event_code='DEPOSIT_APPROVED'",Long.class);
        String original=content.userDetail(contentId,member,"en").get("bodyHtml").toString();
        assertTrue(original.contains("DP-EXAMPLE"));assertTrue(original.contains("10.12345678 USDT"));
        tx.executeWithoutResult(status->messages.save("DEPOSIT_APPROVED",Map.of("translations",Map.of("en",Map.of("title","Changed","bodyHtml","<p>Future body</p>")))));
        assertEquals(original,content.userDetail(contentId,member,"en").get("bodyHtml"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content",Integer.class));
    }
    @Test void completedWithdrawalDeliversOnePersonalMessageWithOriginalNetCurrencyAndImmutableBody() {
        long member=register();
        long withdrawal=data.insert("compute_withdrawal",ComputeData.fields("member_id",member,"currency","TRX","network","TRC20",
                "amount",new BigDecimal("300"),"fee",new BigDecimal("3.12345678"),"net_amount",new BigDecimal("296.87654322"),
                "address","T"+"1".repeat(33),"rule_snapshot","{}","status","APPROVED","order_no","WD-EXAMPLE","created_at",ComputeData.now()));
        tx.executeWithoutResult(status->{
            messages.withdrawalCompleted(withdrawal,member,new BigDecimal("296.87654322"),"TRX");
            messages.withdrawalCompleted(withdrawal,member,new BigDecimal("296.87654322"),"TRX");
        });
        long contentId=jdbc.queryForObject("SELECT content_id FROM compute_system_message_delivery WHERE event_code='WITHDRAWAL_COMPLETED'",Long.class);
        var delivered=content.userDetail(contentId,member,"en");
        String body=delivered.get("bodyHtml").toString();
        assertEquals("MESSAGE",delivered.get("kind"));
        assertTrue(body.contains("WD-EXAMPLE"));assertTrue(body.contains("296.87654322 TRX"));
        assertEquals(0L,content.userList(member+1,Map.of("kind","MESSAGE"),"en").get("total"));
        tx.executeWithoutResult(status->messages.save("WITHDRAWAL_COMPLETED",Map.of("translations",Map.of("en",Map.of("title","Withdrawal complete","bodyHtml","<p>Updated future template</p>")))));
        jdbc.update("UPDATE compute_withdrawal SET order_no='WD-CHANGED' WHERE id=?",withdrawal);
        tx.executeWithoutResult(status->messages.withdrawalCompleted(withdrawal,member,new BigDecimal("1"),"USDT"));
        assertEquals(body,content.userDetail(contentId,member,"en").get("bodyHtml"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_system_message_delivery WHERE event_code='WITHDRAWAL_COMPLETED'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_recipient",Integer.class));
    }
    @Test void registrationKeepsNewMemberUngroupedEvenWhenInviterHasAGroup() {
        long inviter=register();
        jdbc.update("INSERT INTO compute_member_group(id,name,created_at,updated_at) VALUES(77,'邀请人分组',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE compute_member SET group_id=77 WHERE id=?",inviter);
        when(scope.resolveInviter("existing-invite")).thenReturn(inviter);
        var accounts=new ComputeAccountService(jdbc,new PasswordUtils(),mock(ComputeBusinessService.class),scope,messages);
        var response=tx.execute(status->accounts.register(new ComputeAuthRequests.Register("inviteduser",null,null,"abcdefg","existing-invite")));
        long member=((Number)((Map<?,?>)response.get("member")).get("id")).longValue();
        assertEquals(inviter,jdbc.queryForObject("SELECT inviter_id FROM compute_member WHERE id=?",Long.class,member));
        assertNull(jdbc.queryForObject("SELECT group_id FROM compute_member WHERE id=?",Long.class,member));
        assertEquals(77L,jdbc.queryForObject("SELECT group_id FROM compute_member WHERE id=?",Long.class,inviter));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_system_message_delivery WHERE event_code='WELCOME'",Integer.class));
    }
    @Test void missingEnglishCannotBeExplicitlyClearedButOmittedEnglishIsRetained(){
        assertThrows(BusinessException.class,()->tx.execute(status->messages.save("WELCOME",Map.of("translations",Map.of("en",Map.of("title","","bodyHtml","<p>&nbsp;</p>"))))));
        tx.executeWithoutResult(status->messages.save("WELCOME",Map.of("translations",Map.of("fr",Map.of("title","Bienvenue","bodyHtml","<p>Bonjour</p>")))));
        assertTrue(messages.templates().stream().anyMatch(row->row.get("translations").toString().contains("Hello")));
    }
    @Test void failedBusinessTransactionRollsBackMessageAndIdempotencyRecord(){
        long member=register();
        jdbc.update("DELETE FROM compute_content_recipient");jdbc.update("DELETE FROM compute_content");jdbc.update("DELETE FROM compute_system_message_delivery");
        assertThrows(IllegalStateException.class,()->tx.execute(status->{messages.welcome(member);throw new IllegalStateException("隔离事务失败");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_system_message_delivery",Integer.class));
    }
    @Test void disabledTemplateDoesNotCreateMessage(){
        tx.executeWithoutResult(status->messages.save("WELCOME",Map.of("enabled",false)));
        register();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content",Integer.class));
    }
    @Test void templateSixEditorPreservesOtherFieldsAndHistoricalTypeThree() {
        String key="user.systemMessage.welcome.body";
        jdbc.update("INSERT INTO translates(tkey,lang,tvalue,type,prop) VALUES(?,'en','<p>Legacy manual body</p>',3,1)",key);
        var request=new org.token.model.entity.Translation();request.setTkey(key);request.setType(6);request.setProp(1);request.setTvalue("<p>新的中文正文</p>");
        request.setTranslations(Map.of("en","<p>New English body</p>"));request.setSystemMessageEnabled(false);
        tx.executeWithoutResult(status->messages.updateTranslationKey(request));
        assertEquals("<p>Legacy manual body</p>",jdbc.queryForObject("SELECT tvalue FROM translates WHERE type=3 AND tkey=? AND lang='en'",String.class,key));
        assertEquals("<p>New English body</p>",jdbc.queryForObject("SELECT tvalue FROM translates WHERE type=6 AND tkey=? AND lang='en'",String.class,key));
        assertEquals("Welcome",jdbc.queryForObject("SELECT tvalue FROM translates WHERE type=6 AND tkey='user.systemMessage.welcome.title' AND lang='en'",String.class));
        assertFalse(messages.settings().get("WELCOME"));
        request.setType(3);assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->messages.updateTranslationKey(request)));
        request.setType(6);request.setProp(2);assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->messages.updateTranslationKey(request)));
    }
    @Test void partialOptionalLanguageIsRetainedButDeliveryUsesCompleteEnglish() {
        var request=new org.token.model.entity.Translation();request.setTkey("user.systemMessage.welcome.body");request.setType(6);request.setProp(1);request.setTvalue("<p>中文正文</p>");request.setTranslations(Map.of("fr","<p>Corps français</p>"));
        tx.executeWithoutResult(status->messages.updateTranslationKey(request));
        String storedFrench=jdbc.queryForObject("SELECT tvalue FROM translates WHERE type=6 AND tkey=? AND lang='fr'",String.class,request.getTkey());
        assertEquals("<p>Corps français</p>",org.springframework.web.util.HtmlUtils.htmlUnescape(storedFrench));
        long member=register();var row=(Map<?,?>)((List<?>)content.userList(member,Map.of("kind","MESSAGE"),"fr").get("records")).get(0);
        assertEquals("en",row.get("translationLanguage"));assertEquals("Welcome",row.get("title"));
    }
}
