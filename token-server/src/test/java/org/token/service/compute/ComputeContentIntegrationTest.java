package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import org.token.service.compute.ComputeContentHtml;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.config.EnabledLocaleResolver;
import org.token.exception.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 内容发布使用生产服务与事务，所有数据只在 H2 内存中存在。 */
@Tag("regression")
class ComputeContentIntegrationTest {
    JdbcTemplate jdbc;ComputeContentService service;ComputeAgentScope scope;List<Runnable> work;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:content_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);
        TokenTestSchema.create(jdbc, "compute_content","compute_content_recipient","compute_member","compute_member_group");
        jdbc.execute("CREATE TABLE translate_language(lang VARCHAR(20),status INT)");
        jdbc.update("INSERT INTO translate_language VALUES('zh',1),('en',1)");
        TokenTestSchema.apply(jdbc);
        TokenTestSchema.translation(jdbc);
        jdbc.update("INSERT INTO compute_member_group(id,name,created_at,updated_at) VALUES(2,'内容分组',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_member(id,username,enabled,group_id,inviter_id,password_hash,invite_code,created_at,updated_at) VALUES(1,'甲',1,2,NULL,'test','content1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'乙',1,2,NULL,'test','content2',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(3,'停用',0,2,1,'test','content3',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        scope=mock(ComputeAgentScope.class);
        when(scope.relations()).thenReturn(new ComputeMemberRelations(jdbc));
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(true,List.of()));
        when(scope.currentRootMemberId()).thenReturn(null);
        EnabledLocaleResolver locales=mock(EnabledLocaleResolver.class);when(locales.resolve(anyString())).thenAnswer(i->i.getArgument(0));
        when(locales.normalize(anyString())).thenCallRealMethod();
        when(locales.enabledLanguages()).thenReturn(List.of(new org.token.model.response.I18nLanguageOption("中文","zh"),new org.token.model.response.I18nLanguageOption("English","en")));
        when(locales.lookupCandidates("zh")).thenReturn(List.of("zh"));when(locales.lookupCandidates("en")).thenReturn(List.of("en","zh"));
        work=new ArrayList<>();service=new ComputeContentService(jdbc,new FixtureComputeData(jdbc,new ObjectMapper()),scope,locales,work::add,new DataSourceTransactionManager(source));
    }

    Map<String,Object> input(String kind){var value=new LinkedHashMap<String,Object>(Map.of("kind",kind,"title","内容标题","bodyHtml","<p>正文 <b>重点</b></p>","enabled",true,"targetType","TASK".equals(kind)?"USER":"ALL","translations",Map.of("en",Map.of("title","Content title","bodyHtml","<p>Body</p>"))));if("TASK".equals(kind))value.put("memberUsernames",List.of("甲","乙"));return value;}
    long id(Map<String,Object> value){return ((Number)value.get("id")).longValue();}

    @Test void adminListsAlwaysRequireOneKindAndNeverMixPublishedMessagesWithArticles() {
        for(String kind:List.of("ARTICLE","ANNOUNCEMENT","MESSAGE","TASK"))service.save(input(kind),1);
        for(String kind:List.of("ARTICLE","ANNOUNCEMENT","MESSAGE","TASK")) {
            var page=service.adminList(Map.of("kind",kind,"keyword","内容"));
            assertEquals(1L,page.get("total"));
            var rows=(List<Map<String,Object>>)page.get("records");
            assertTrue(rows.stream().allMatch(row->kind.equals(row.get("kind"))));
        }
        assertThrows(BusinessException.class,()->service.adminList(Map.of()));
        assertThrows(BusinessException.class,()->service.adminList(Map.of("kind","TASK","contentKey","ABOUT")));
        long article=id(service.save(input("ARTICLE"),1));
        assertThrows(BusinessException.class,()->service.recipients(article,Map.of()));
    }

    @Test void articleActivationIsImmediateAndPreservesPurposeAndTranslation() {
        var article=input("ARTICLE"); article.put("contentKey","FAQ");article.put("enabled",false);long terms=id(service.save(article,1));
        assertEquals(0L,service.userList(null,Map.of("kind","ARTICLE","contentKey","FAQ"),"en").get("total"));
        service.setEnabled(terms,true);
        assertEquals(1L,service.userList(null,Map.of("kind","ARTICLE","contentKey","FAQ"),"en").get("total"));
        assertEquals(0L,service.userList(null,Map.of("kind","ARTICLE","contentKey","PRIVACY"),"en").get("total"));
        service.recall(terms);assertEquals(0L,service.userList(null,Map.of("kind","ARTICLE","contentKey","FAQ"),"en").get("total"));
        article.put("contentKey","UNSAFE");assertThrows(BusinessException.class,()->service.save(article,1));
    }

    @Test void ordinaryArticlesFaqAndFixedDocumentsHaveSeparateListsAndDetailPurposes() {
        long general=id(service.save(input("ARTICLE"),1));
        var faqInput=input("ARTICLE");faqInput.put("contentKey","FAQ");long faq=id(service.save(faqInput,1));
        List<Long> fixedIds=new ArrayList<>();
        for(String purpose:List.of("ABOUT","TERMS","PRIVACY")){
            long fixed=id(service.save(input("ARTICLE"),1));fixedIds.add(fixed);
            jdbc.update("UPDATE compute_content SET content_key=? WHERE id=?",purpose,fixed);
        }
        long announcement=id(service.save(input("ANNOUNCEMENT"),1));
        var ordinary=(List<Map<String,Object>>)service.userList(null,Map.of("kind","ARTICLE"),"en").get("records");
        assertEquals(List.of(general),ordinary.stream().map(this::id).toList());
        var faqs=(List<Map<String,Object>>)service.userList(null,Map.of("kind","ARTICLE","contentKey","FAQ"),"en").get("records");
        assertEquals(List.of(faq),faqs.stream().map(this::id).toList());
        assertEquals(general,id(service.userDetail(general,null,"en",Map.of("kind","ARTICLE","contentKey","GENERAL"))));
        assertEquals(faq,id(service.userDetail(faq,null,"en",Map.of("kind","ARTICLE","contentKey","FAQ"))));
        assertThrows(BusinessException.class,()->service.userDetail(faq,null,"en",Map.of("kind","ARTICLE")));
        assertThrows(BusinessException.class,()->service.userDetail(general,null,"en",Map.of("kind","ARTICLE","contentKey","FAQ")));
        assertThrows(BusinessException.class,()->service.userDetail(announcement,null,"en",Map.of("kind","ARTICLE","contentKey","GENERAL")));
        for(long fixed:fixedIds)assertThrows(BusinessException.class,()->service.userDetail(fixed,null,"en",Map.of("kind","ARTICLE","contentKey","GENERAL")));
        assertThrows(BusinessException.class,()->service.userList(null,Map.of("kind","ANNOUNCEMENT","contentKey","FAQ"),"en"));
    }

    @Test void permanentArticlesCannotBeCreatedCopiedDeletedOrRepurposed() {
        for(String key:List.of("TERMS","PRIVACY","ABOUT")) {
            var body=input("ARTICLE");body.put("contentKey",key);
            assertEquals("server.compute.v8.fixedContentProtected",assertThrows(BusinessException.class,()->service.save(body,1)).getMessage());
            // 安装增量负责建立固定记录，应用启动及新增接口都不会创建固定文章。
            long seeded=id(service.save(input("ARTICLE"),1));
            jdbc.update("UPDATE compute_content SET content_key=?,enabled=0 WHERE id=?",key,seeded);
            body.put("id",seeded);body.put("enabled",true);
            assertEquals("PUBLISHED",service.save(body,1).get("status"));
            assertEquals(1L,service.userList(null,Map.of("kind","ARTICLE","contentKey",key),"en").get("total"));
            assertThrows(BusinessException.class,()->service.delete(seeded));
            assertThrows(BusinessException.class,()->service.recall(seeded));
            assertThrows(BusinessException.class,()->service.publish(seeded));
            body.put("contentKey","GENERAL");assertThrows(BusinessException.class,()->service.save(body,1));
            body.put("contentKey",key);body.put("enabled",false);
            service.save(body,1);
            assertEquals(0L,service.userList(null,Map.of("kind","ARTICLE","contentKey",key),"en").get("total"));
            assertEquals(0,jdbc.queryForObject("SELECT deleted FROM compute_content WHERE id=?",Integer.class,seeded));
        }
        var ordinary=input("ARTICLE");ordinary.put("id",id(service.save(input("ARTICLE"),1)));ordinary.put("contentKey","TERMS");
        assertThrows(BusinessException.class,()->service.save(ordinary,1));
    }

    @Test void helpViewCombinesOnlyQuestionsAndOrdinaryArticlesBeforePagination() {
        List<Long> expected=new ArrayList<>();
        // 两种用途交错，并跨越三页；不能分别取首页后在客户端拼接。
        for(int index=0;index<47;index++){
            var body=input("ARTICLE");body.put("contentKey",index%2==0?"FAQ":"GENERAL");body.put("sort",index);
            expected.add(id(service.save(body,1)));
        }
        Collections.reverse(expected);
        for(String purpose:List.of("ABOUT","TERMS","PRIVACY")){
            long fixed=id(service.save(input("ARTICLE"),1));
            jdbc.update("UPDATE compute_content SET content_key=?,sort=1000 WHERE id=?",purpose,fixed);
        }
        for(String kind:List.of("ANNOUNCEMENT","MESSAGE","TASK"))service.save(input(kind),1);
        for(String purpose:List.of("FAQ","GENERAL")){
            var disabled=input("ARTICLE");disabled.put("contentKey",purpose);disabled.put("enabled",false);service.save(disabled,1);
            var future=input("ARTICLE");future.put("contentKey",purpose);future.put("startsAt","2099-01-01T00:00:00Z");service.save(future,1);
            var expired=input("ARTICLE");expired.put("contentKey",purpose);expired.put("endsAt","2020-01-01T00:00:00Z");service.save(expired,1);
            long deleted=id(service.save(input("ARTICLE"),1));jdbc.update("UPDATE compute_content SET content_key=?,deleted=1 WHERE id=?",purpose,deleted);
        }
        List<Long> actual=new ArrayList<>();
        for(int page=1;page<=3;page++){
            var result=service.userList(null,Map.of("kind","ARTICLE","view","HELP","page",String.valueOf(page),"size","20"),"en");
            assertEquals(47L,result.get("total"));
            var rows=(List<Map<String,Object>>)result.get("records");
            assertEquals(page==3?7:20,rows.size());
            assertTrue(rows.stream().allMatch(row->"ARTICLE".equals(row.get("kind"))&&Set.of("FAQ","GENERAL").contains(row.get("contentKey"))));
            actual.addAll(rows.stream().map(this::id).toList());
        }
        assertEquals(expected,actual);
        assertEquals(47,new HashSet<>(actual).size());
        assertTrue(((List<?>)service.userList(null,Map.of("kind","ARTICLE","view","HELP","page","4","size","20"),"en").get("records")).isEmpty());
        assertEquals(24L,service.userList(null,Map.of("kind","ARTICLE","contentKey","FAQ"),"en").get("total"));
        assertEquals(23L,service.userList(null,Map.of("kind","ARTICLE"),"en").get("total"));
    }

    @Test void helpViewRetainsSharedOrderingAndRejectsPurposeOrKindOverrides() {
        var general=input("ARTICLE");general.put("sort",100);
        long ordinary=id(service.save(general,1));
        var faq=input("ARTICLE");faq.put("contentKey","FAQ");faq.put("pinned",true);faq.put("sort",1);
        long question=id(service.save(faq,1));
        var result=service.userList(null,Map.of("kind","ARTICLE","view","HELP"),"en");
        assertEquals(List.of(question,ordinary),((List<Map<String,Object>>)result.get("records")).stream().map(this::id).toList());
        for(String purpose:List.of("GENERAL","FAQ","ABOUT","TERMS","PRIVACY","HELP")){
            assertThrows(BusinessException.class,()->service.userList(null,Map.of("kind","ARTICLE","view","HELP","contentKey",purpose),"en"));
        }
        assertThrows(BusinessException.class,()->service.userList(null,Map.of("kind","ARTICLE","view","ALL"),"en"));
        assertThrows(BusinessException.class,()->service.userList(null,Map.of("kind","ANNOUNCEMENT","view","HELP"),"en"));
        assertThrows(BusinessException.class,()->service.userList(1L,Map.of("kind","MESSAGE","view","HELP"),"en"));
        assertThrows(BusinessException.class,()->service.userList(1L,Map.of("kind","TASK","view","HELP"),"en"));
        assertThrows(BusinessException.class,()->service.adminList(Map.of("kind","HELP")));
        assertThrows(BusinessException.class,()->service.save(input("HELP"),1));
    }

    @Test void fixedPurposeUniqueIndexRejectsConcurrentDuplicateWriters() {
        long first=id(service.save(input("ARTICLE"),1));long second=id(service.save(input("ARTICLE"),1));
        jdbc.update("UPDATE compute_content SET content_key='TERMS' WHERE id=?",first);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update("UPDATE compute_content SET content_key='TERMS' WHERE id=?",second));
        assertEquals("GENERAL",jdbc.queryForObject("SELECT content_key FROM compute_content WHERE id=?",String.class,second));
    }

    @Test void asynchronousDeliveryIsScopedAndIdempotent() {
        when(scope.currentRootMemberId()).thenReturn(1L);when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(1L)));
        var body=input("MESSAGE");body.put("targetType","GROUP");body.put("groupId",2);
        long id=id(service.save(body,1));assertEquals("PUBLISHING",service.publish(id).get("status"));service.publish(id);assertEquals(1,work.size());
        work.remove(0).run();assertEquals("PUBLISHED",service.adminDetail(id).get("status"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_recipient",Integer.class));
        assertEquals("内容标题",service.userDetail(id,1L,"zh").get("title"));assertThrows(BusinessException.class,()->service.userDetail(id,2L,"zh"));
        service.mark(id,1L,false);service.mark(id,1L,false);assertEquals(1,jdbc.queryForObject("SELECT read_count FROM compute_content WHERE id=?",Integer.class,id));
        assertThrows(BusinessException.class,()->service.mark(id,1L,true));
        service.publish(id);assertTrue(work.isEmpty());
    }

    @Test void tasksCompleteOnceAndRecallStopsAccess() {
        long id=id(service.save(input("TASK"),1));service.publish(id);work.remove(0).run();
        service.mark(id,1L,true);service.mark(id,1L,true);
        assertEquals(1,jdbc.queryForObject("SELECT completed_count FROM compute_content WHERE id=?",Integer.class,id));
        assertThrows(BusinessException.class,()->service.delete(id));service.recall(id);
        assertThrows(BusinessException.class,()->service.userDetail(id,1L,"zh"));service.delete(id);
        assertEquals(1,jdbc.queryForObject("SELECT deleted FROM compute_content WHERE id=?",Integer.class,id));
    }

    @Test void sixReferenceModesFilterRealRecipientsAndTasksRejectAllTargets() {
        var invalid=input("TASK");invalid.put("targetType","ALL");assertEquals("server.compute.v6.taskTargetRequired",assertThrows(BusinessException.class,()->service.save(invalid,1)).getMessage());
        for(int mode=1;mode<=6;mode++){var body=input("TASK");body.put("taskMode",mode);body.put("showHome",true);long id=id(service.save(body,1));service.publish(id);work.remove(0).run();}
        for(var entry:Map.of("INVEST",List.of(1,3,4,5),"WITHDRAW",List.of(2,3,4,6),"PERSONAL",List.of(4,5,6),"HOME",List.of(1,2,3,4,5,6)).entrySet()){
            var page=service.userList(1L,Map.of("kind","TASK","scene",entry.getKey()),"en");
            var rows=(List<Map<String,Object>>)page.get("records");assertEquals(entry.getValue().size(),rows.size());assertEquals(new HashSet<>(entry.getValue()),new HashSet<>(rows.stream().map(row->((Number)row.get("taskMode")).intValue()).toList()));
        }
        var personal=(List<Map<String,Object>>)service.userList(1L,Map.of("kind","TASK","scene","PERSONAL"),"en").get("records");service.mark(id(personal.get(0)),1L,false);
        assertEquals(2L,((Number)service.userList(1L,Map.of("kind","TASK","scene","PERSONAL"),"en").get("total")).longValue());
    }

    @Test void usernameTargetsResolveToStableIdsAndRemainScopeChecked() {
        var body=input("TASK");body.put("memberUsernames",List.of("甲"));long id=id(service.save(body,1));assertEquals(List.of(1L),service.adminDetail(id).get("memberIds"));assertEquals(List.of("甲"),service.adminDetail(id).get("memberUsernames"));
        body.remove("id");body.put("memberUsernames",List.of("不存在"));assertThrows(BusinessException.class,()->service.save(body,1));
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(1L)));body.put("memberUsernames",List.of("乙"));assertThrows(BusinessException.class,()->service.save(body,1));
    }

    @Test void personalBranchReadDoesNotCompleteActionTasksAndOnlyMatchingScenesCanComplete() {
        var guard=new ComputeContentTaskGuard(new FixtureComputeData(jdbc,new ObjectMapper()));
        var ids=new LinkedHashMap<Integer,Long>();
        for(int mode=1;mode<=6;mode++) {
            var body=input("TASK");body.put("taskMode",mode);
            long contentId=id(service.save(body,1));ids.put(mode,contentId);service.publish(contentId);work.remove(0).run();
        }
        var messages=(List<Map<String,Object>>)service.userList(1L,Map.of("kind","MESSAGE"),"en").get("records");
        assertEquals(Set.of(4,5,6),new HashSet<>(messages.stream().map(row->((Number)row.get("taskMode")).intValue()).toList()));
        for(var message:messages)service.mark(id(message),1,false);
        assertEquals(3L,service.userList(1L,Map.of("kind","MESSAGE"),"en").get("total"));
        assertEquals(4L,service.userList(1L,Map.of("kind","TASK","scene","INVEST"),"en").get("total"));
        assertEquals("server.compute.v17.taskRestricted",assertThrows(BusinessException.class,()->guard.assertSceneCompleted(1,"INVEST")).getMessage());
        assertThrows(BusinessException.class,()->service.mark(ids.get(1),1,true,"WITHDRAW"));
        for(int mode:List.of(1,3,4,5)){service.mark(ids.get(mode),1,true,"INVEST");service.mark(ids.get(mode),1,true,"INVEST");}
        assertThrows(BusinessException.class,()->guard.assertSceneCompleted(1,"INVEST"));
        assertEquals(4L,service.userList(1L,Map.of("kind","TASK","scene","INVEST"),"en").get("total"));
        assertThrows(BusinessException.class,()->guard.assertSceneCompleted(1,"WITHDRAW"));
        for(int mode:List.of(2,6))service.mark(ids.get(mode),1,true,"WITHDRAW");
        assertThrows(BusinessException.class,()->guard.assertSceneCompleted(1,"WITHDRAW"));
        assertEquals(6,jdbc.queryForObject("SELECT SUM(completed_count) FROM compute_content",Integer.class));
        for(long contentId:ids.values())service.releaseRestriction(contentId,1,1);
        assertDoesNotThrow(()->guard.assertSceneCompleted(1,"INVEST"));
        assertDoesNotThrow(()->guard.assertSceneCompleted(1,"WITHDRAW"));
        assertEquals(0L,service.userList(1L,Map.of("kind","TASK","scene","INVEST"),"en").get("total"));
        assertThrows(BusinessException.class,()->guard.assertSceneCompleted(2,"INVEST"));
        assertThrows(BusinessException.class,()->guard.assertSceneCompleted(1,"PRODUCT"));
        assertDoesNotThrow(()->guard.assertSceneCompleted(3,"INVEST"));
    }

    @Test void liftingOneRecipientRestrictionIsScopedAuditedAndDoesNotEraseDeliveryHistory() {
        long task=id(service.save(input("TASK"),1));service.publish(task);work.remove(0).run();
        service.mark(task,1,true,"INVEST");
        jdbc.update("UPDATE compute_content SET owner_member_id=1 WHERE id=?",task);
        when(scope.currentRootMemberId()).thenReturn(1L);
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(1L)));
        assertThrows(BusinessException.class,()->service.releaseRestriction(task,2,1));
        service.releaseRestriction(task,1,1);
        var first=jdbc.queryForMap("SELECT restriction_released_at,restriction_released_by FROM compute_content_recipient WHERE content_id=? AND member_id=1",task);
        service.releaseRestriction(task,1,2);
        assertEquals(first,jdbc.queryForMap("SELECT restriction_released_at,restriction_released_by FROM compute_content_recipient WHERE content_id=? AND member_id=1",task));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_recipient WHERE content_id=?",Integer.class,task));
        assertNotNull(jdbc.queryForObject("SELECT completed_at FROM compute_content_recipient WHERE content_id=? AND member_id=1",java.sql.Timestamp.class,task));
        long message=id(service.save(input("MESSAGE"),1));
        assertThrows(BusinessException.class,()->service.releaseRestriction(message,1,1));
    }

    @Test void actionGuardIgnoresExpiredDisabledRecalledAndFutureTasks() {
        var guard=new ComputeContentTaskGuard(new FixtureComputeData(jdbc,new ObjectMapper()));
        for(String change:List.of("ends_at=TIMESTAMP '2000-01-01 00:00:00'","starts_at=TIMESTAMP '2099-01-01 00:00:00'","enabled=0","status='RECALLED'","deleted=1")) {
            long contentId=id(service.save(input("TASK"),1));service.publish(contentId);work.remove(0).run();
            jdbc.update("UPDATE compute_content SET "+change+" WHERE id=?",contentId);
        }
        assertDoesNotThrow(()->guard.assertSceneCompleted(1,"INVEST"));
        assertDoesNotThrow(()->guard.assertSceneCompleted(1,"WITHDRAW"));
    }

    @Test void homePopupQueueIsScopedOrderedAndSeparateFromReadAndCompletion() {
        var announcement=input("ANNOUNCEMENT");announcement.put("homePopupEnabled",true);announcement.put("sort",5);
        long notice=id(service.save(announcement,1));service.publish(notice);
        var message=input("MESSAGE");message.put("homePopupEnabled",true);message.put("sort",20);message.put("targetType","USER");message.put("memberUsernames",List.of("甲"));
        long personal=id(service.save(message,1));service.publish(personal);work.remove(0).run();
        var task=input("TASK");task.put("homePopupEnabled",true);task.put("pinned",true);task.put("sort",1);
        long action=id(service.save(task,1));service.publish(action);work.remove(0).run();
        var disabled=input("ANNOUNCEMENT");disabled.put("showHome",true);long legacy=id(service.save(disabled,1));service.publish(legacy);
        var article=input("ARTICLE");article.put("homePopupEnabled",true);long articleId=id(service.save(article,1));service.publish(articleId);
        assertEquals(List.of(notice),service.publicHomePopups("en").stream().map(this::id).toList());
        var rows=(List<Map<String,Object>>)service.homePopups(1,Map.of(),"en").get("records");
        assertEquals(List.of(personal,notice),rows.stream().map(this::id).toList());
        assertEquals(1L,service.homePopups(2,Map.of(),"en").get("total"));
        assertThrows(BusinessException.class,()->service.markHomePopup(personal,2,1));
        assertThrows(BusinessException.class,()->service.markHomePopup(legacy,1,1));
        assertThrows(BusinessException.class,()->service.markHomePopup(articleId,1,1));
        assertThrows(BusinessException.class,()->service.markHomePopup(action,1,1));
        // 升级前留有首页弹窗标志的任务也不能在首页触发。
        jdbc.update("UPDATE compute_content SET home_popup_enabled=1 WHERE id=?",action);
        assertEquals(2L,service.homePopups(1,Map.of(),"en").get("total"));
        service.markHomePopup(personal,1,1);service.markHomePopup(personal,1,1);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_popup_seen",Integer.class));
        assertNull(jdbc.queryForObject("SELECT read_at FROM compute_content_recipient WHERE content_id=? AND member_id=1",java.sql.Timestamp.class,personal));
        assertNull(jdbc.queryForObject("SELECT completed_at FROM compute_content_recipient WHERE content_id=? AND member_id=1",java.sql.Timestamp.class,action));
        assertEquals(1L,service.homePopups(1,Map.of(),"en").get("total"));
        message.put("id",personal);message.put("title","更新后的信息标题");service.save(message,1);
        assertEquals(2L,service.homePopups(1,Map.of(),"en").get("total"));
        assertEquals("server.compute.v9.popupUpdated",assertThrows(BusinessException.class,()->service.markHomePopup(personal,1,1)).getMessage());
        service.markHomePopup(personal,1,2);
        assertEquals(1L,service.homePopups(1,Map.of(),"en").get("total"));
        service.markHomePopup(notice,1,1);
        // 公告在下次首页访问仍返回，个人信息保留一次弹出规则；前端负责当前访问内去重。
        assertEquals(List.of(notice),((List<Map<String,Object>>)service.homePopups(1,Map.of(),"en").get("records")).stream().map(this::id).toList());
        assertEquals(List.of(notice),service.publicHomePopups("en").stream().map(this::id).toList());
    }

    @Test void popupOrderUsesPriorityThenEffectiveTimeAndGuestPayloadHidesAudience() {
        List<Long> ids=new ArrayList<>();
        for(int index=0;index<3;index++) {
            var body=input("ANNOUNCEMENT");body.put("homePopupEnabled",true);body.put("sort",index==0?10:5);
            long contentId=id(service.save(body,1));service.publish(contentId);ids.add(contentId);
            jdbc.update("UPDATE compute_content SET starts_at=? WHERE id=?",java.time.LocalDateTime.of(2020,1,index+1,0,0),contentId);
        }
        var rows=service.publicHomePopups("en");
        assertEquals(List.of(ids.get(0),ids.get(2),ids.get(1)),rows.stream().map(this::id).toList());
        assertTrue(rows.stream().noneMatch(row->row.containsKey("memberIds")||row.containsKey("createdBy")||row.containsKey("ownerMemberId")));
        var next=(List<Map<String,Object>>)service.publicHomePopupPage(Map.of("page","2","size","2"),"en").get("records");
        assertEquals(List.of(ids.get(1)),next.stream().map(this::id).toList());
        jdbc.update("UPDATE compute_content SET starts_at=TIMESTAMP '2099-01-01 00:00:00' WHERE id=?",ids.get(0));
        assertEquals(List.of(ids.get(2),ids.get(1)),service.publicHomePopups("en").stream().map(this::id).toList());
    }

    @Test void publishedAudienceCannotBeChangedAndTargetScopeIsChecked() {
        var body=input("MESSAGE");body.put("targetType","USER");body.put("memberIds",List.of(1));
        long id=id(service.save(body,1));service.publish(id);work.remove(0).run();
        body.put("id",id);body.put("memberIds",List.of(2));assertThrows(BusinessException.class,()->service.save(body,1));
        assertEquals(List.of(1L),service.adminDetail(id).get("memberIds"));
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(1L)));body.remove("id");
        assertThrows(BusinessException.class,()->service.save(body,1));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content",Integer.class));
    }

    @Test void publicContentHonorsTimeStatusAndLanguageFallback() {
        var body=input("ANNOUNCEMENT");body.put("translations",Map.of("en",Map.of("title","Announcement","bodyHtml","<p>Hello</p>")));
        long id=id(service.save(body,1));
        assertEquals("Announcement",service.userDetail(id,null,"en").get("title"));assertFalse(service.userDetail(id,null,"en").containsKey("translationsJson"));
        body.put("id",id);body.put("startsAt","2099-01-01T00:00:00Z");service.save(body,1);assertThrows(BusinessException.class,()->service.userDetail(id,null,"zh"));
    }

    @Test void interruptedDeliveryCanResumeWithoutDuplicatingRecipients() {
        long id=id(service.save(input("TASK"),1));service.publish(id);work.clear();
        jdbc.update("INSERT INTO compute_content_recipient(content_id,member_id,created_at) VALUES(?,1,CURRENT_TIMESTAMP)",id);
        jdbc.update("UPDATE compute_content SET processed_id=1,recipient_count=1,updated_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?",id);
        service.recoverInterruptedDeliveries();assertEquals("FAILED",service.adminDetail(id).get("status"));service.publish(id);work.remove(0).run();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_recipient WHERE content_id=?",Integer.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT recipient_count FROM compute_content WHERE id=?",Integer.class,id));
    }

    @Test void publicationBoundsTheAudienceAndDisabledOwnerStopsDelivery() {
        long first=id(service.save(input("MESSAGE"),1));service.publish(first);
        jdbc.update("INSERT INTO compute_member(id,username,enabled,group_id,inviter_id,password_hash,invite_code,created_at,updated_at) VALUES(4,'新注册',1,2,1,'test','content4',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");work.remove(0).run();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_recipient WHERE content_id=?",Integer.class,first));
        when(scope.currentRootMemberId()).thenReturn(1L);
        long second=id(service.save(input("MESSAGE"),1));service.publish(second);
        jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=1");work.remove(0).run();
        assertEquals("FAILED",service.adminDetail(second).get("status"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_recipient WHERE content_id=?",Integer.class,second));
    }

    @Test void savingEnabledContentActivatesEveryKindWithoutPublishAndDisablingStopsVisibility() {
        for(String kind:List.of("ARTICLE","ANNOUNCEMENT","MESSAGE","TASK")) {
            var body=input(kind);long contentId=id(service.save(body,1));
            if(Set.of("MESSAGE","TASK").contains(kind)) {
                assertEquals("PUBLISHING",service.adminDetail(contentId).get("status"));
                assertEquals(1,work.size());work.remove(0).run();
            }
            assertEquals("PUBLISHED",service.adminDetail(contentId).get("status"));
            Long member=Set.of("MESSAGE","TASK").contains(kind)?1L:null;
            assertEquals(contentId,id(service.userDetail(contentId,member,"en")));
            service.setEnabled(contentId,false);
            assertThrows(BusinessException.class,()->service.userDetail(contentId,member,"en"));
            assertEquals(1L,service.adminList(Map.of("kind",kind,"enabled","false")).get("total"));
            assertEquals(0L,service.adminList(Map.of("kind",kind,"enabled","true")).get("total"));
            body.put("id",contentId);body.put("title","启用后的新标题");service.save(body,1);
            assertEquals("启用后的新标题",service.userDetail(contentId,member,"zh").get("title"));
            assertTrue(work.isEmpty());
        }
    }

    @Test void disabledSaveRemainsPrivateUntilEnabledAndLegacyPublishUsesSameSwitch() {
        var body=input("MESSAGE");body.put("enabled",false);
        long contentId=id(service.save(body,1));assertTrue(work.isEmpty());
        assertEquals("DRAFT",service.adminDetail(contentId).get("status"));
        service.publish(contentId);service.publish(contentId);assertEquals(1,work.size());work.remove(0).run();
        service.recall(contentId);assertFalse(ComputeData.enabled(service.adminDetail(contentId).get("enabled")));
        service.setEnabled(contentId,true);work.remove(0).run();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_recipient WHERE content_id=?",Integer.class,contentId));
    }

    @Test void disablePausesDeliveryAndReenablePreservesAudienceReadAndRestrictionReleaseHistory() {
        long contentId=id(service.save(input("TASK"),1));
        service.setEnabled(contentId,false);work.remove(0).run();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_recipient WHERE content_id=?",Integer.class,contentId));
        jdbc.update("INSERT INTO compute_member(id,username,enabled,password_hash,invite_code,created_at,updated_at) VALUES(4,'之后注册',1,'test','content4',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        service.setEnabled(contentId,true);service.setEnabled(contentId,true);assertEquals(1,work.size());work.remove(0).run();
        service.mark(contentId,1,true,"INVEST");service.releaseRestriction(contentId,1,9);
        var history=jdbc.queryForList("SELECT * FROM compute_content_recipient WHERE content_id=? ORDER BY member_id",contentId);
        service.setEnabled(contentId,false);service.setEnabled(contentId,true);assertTrue(work.isEmpty());
        assertEquals(history,jdbc.queryForList("SELECT * FROM compute_content_recipient WHERE content_id=? ORDER BY member_id",contentId));
        assertEquals(2,history.size());
        var guard=new ComputeContentTaskGuard(new FixtureComputeData(jdbc,new ObjectMapper()));
        assertDoesNotThrow(()->guard.assertSceneCompleted(1,"INVEST"));
        assertThrows(BusinessException.class,()->guard.assertSceneCompleted(2,"INVEST"));
        service.setEnabled(contentId,false);assertDoesNotThrow(()->guard.assertSceneCompleted(2,"INVEST"));
        service.delete(contentId);assertEquals(history,jdbc.queryForList("SELECT * FROM compute_content_recipient WHERE content_id=? ORDER BY member_id",contentId));
    }

    @Test void contentJumpDefaultsToNewWindowAndInternalUrlsRejectExternalAndEncodedEscapes() {
        long defaultId=id(service.save(input("ARTICLE"),1));
        assertEquals("NEW_WINDOW",service.adminDetail(defaultId).get("jumpMode"));
        var body=input("ARTICLE");body.put("jumpMode","INTERNAL");body.put("jumpUrl","/deposit?currency=USDT#address");
        long contentId=id(service.save(body,1));assertEquals("/deposit?currency=USDT#address",service.userDetail(contentId,null,"en").get("jumpUrl"));
        for(String value:List.of("https://example.invalid/deposit","//example.invalid","/%2fexample.invalid","/%5cexample.invalid","/%252fexample.invalid","/deposit%0a")) {
            body.put("jumpUrl",value);assertThrows(BusinessException.class,()->service.save(body,1),value);
        }
        body.put("jumpMode","NEW_WINDOW");body.put("jumpUrl","https://example.invalid/deposit");assertDoesNotThrow(()->service.save(body,1));
        body.put("jumpMode","UNKNOWN");assertThrows(BusinessException.class,()->service.save(body,1));
    }

    @Test void unsafeHtmlAndUrlsAreRemovedWithoutBreakingMarkup() {
        String html=ComputeContentHtml.sanitize("<p onclick='bad()'>正文<a href='javascript:alert(1)'>链接</a><img src='javascript:bad' onerror='bad()'><script>alert(1)</script></p>");
        assertFalse(html.contains("javascript:"));assertFalse(html.contains("onclick"));assertFalse(html.contains("onerror"));assertFalse(html.contains("<script"));assertTrue(html.contains("<a>链接</a>"));
        assertEquals("/token-user/content",ComputeContentHtml.safeUrl("/token-user/content"));
        assertThrows(BusinessException.class,()->ComputeContentHtml.safeUrl("//example.invalid"));
    }

    @Test void languageKeysAreNormalizedWhenSavingAndReadingHistoricalJson() {
        var body=input("ARTICLE");body.put("translations",Map.of("EN",Map.of("title","English","bodyHtml","<p>English</p>")));
        long id=id(service.save(body,1));service.publish(id);
        assertEquals("English",service.userDetail(id,null,"en").get("title"));
        jdbc.update("UPDATE compute_content SET translations_json=? WHERE id=?","{\"EN\":{\"title\":\"Historical\",\"bodyHtml\":\"<p>Historical</p>\"}}",id);
        assertEquals("Historical",service.userDetail(id,null,"en").get("title"));
    }

    @Test void publishedTeamAudienceKeepsItsOriginalIdentityWhenEditingContent() {
        long id=id(service.save(input("MESSAGE"),1));
        jdbc.update("UPDATE compute_content SET target_type='AGENT',target_member_id=1,status='PUBLISHED' WHERE id=?",id);
        var changed=input("MESSAGE");changed.put("id",id);changed.put("targetType","AGENT");
        changed.put("targetMemberId",1L);changed.put("title","更新后的正文标题");
        service.save(changed,1);
        var row=service.adminDetail(id);
        assertEquals(1L,((Number)row.get("targetMemberId")).longValue());
        assertEquals("更新后的正文标题",row.get("title"));
        changed.put("targetMemberId",2L);
        assertThrows(BusinessException.class,()->service.save(changed,1));
    }

    @Test void multipleRootsNeedExplicitOwnerAndCannotMixAudienceFromOtherRoots() {
        when(scope.currentRootMemberId()).thenReturn(1L);
        var body=input("MESSAGE");body.put("targetType","USER");body.put("memberIds",List.of(2));
        assertThrows(BusinessException.class,()->service.save(body,1));
        body.put("ownerMemberId",2L);
        long id=id(service.save(body,1));service.publish(id);work.remove(0).run();
        assertEquals(List.of(2L),jdbc.query("SELECT member_id FROM compute_content_recipient WHERE content_id=?",(rs,index)->rs.getLong(1),id));
        var agent=input("MESSAGE");agent.put("targetType","AGENT");agent.put("targetMemberId",2L);
        assertThrows(BusinessException.class,()->service.save(agent,1));
    }

    @Test void ungroupedAudienceIncludesOnlyMembersWithoutAnAssignedGroup() {
        jdbc.update("UPDATE compute_member SET group_id=NULL WHERE id=2");
        var body=input("TASK");body.put("targetType","GROUP");body.put("groupId",0);
        long id=id(service.save(body,1));service.publish(id);work.remove(0).run();
        assertEquals(List.of(2L),jdbc.query("SELECT member_id FROM compute_content_recipient WHERE content_id=?",(rs,index)->rs.getLong(1),id));
    }
}
