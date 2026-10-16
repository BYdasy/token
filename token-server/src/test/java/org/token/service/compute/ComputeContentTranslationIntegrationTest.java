package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.config.EnabledLocaleResolver;
import org.token.model.response.I18nLanguageOption;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.tools.translation.TranslationUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实内容事务和 H2 内存表；供应商与工作队列可控，不访问外网或实际数据库。 */
@Tag("regression")
class ComputeContentTranslationIntegrationTest {
    private JdbcTemplate jdbc;
    private ComputeContentService content;
    private ComputeContentTranslationService translation;
    private ComputeAgentScope scope;
    private TranslationUtils supplier;
    private Queue<Runnable> work;
    private EnabledLocaleResolver locales;
    private FixtureComputeData data;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:content_translation_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        TokenTestSchema.create(jdbc, "compute_content", "compute_content_recipient", "compute_content_translation_task", "compute_content_translation_item");
        TokenTestSchema.create(jdbc,"compute_member");
        jdbc.execute("CREATE TABLE translate_language(lang VARCHAR(40),status INT)");
        jdbc.update("INSERT INTO translate_language VALUES('zh',1),('en',1),('EN_US',1),('es',1)");
        TokenTestSchema.apply(jdbc);
        TokenTestSchema.content(jdbc);
        TokenTestSchema.activities(jdbc);
        TokenTestSchema.translation(jdbc);
        data = new FixtureComputeData(jdbc, new ObjectMapper());
        scope = mock(ComputeAgentScope.class);
        when(scope.relations()).thenReturn(new ComputeMemberRelations(jdbc));
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(true,List.of()));
        locales = mock(EnabledLocaleResolver.class);
        when(locales.normalize(anyString())).thenCallRealMethod();
        when(locales.resolve(anyString())).thenAnswer(call -> locales.normalize(call.getArgument(0)));
        when(locales.enabledLanguages()).thenReturn(List.of(new I18nLanguageOption("中文", "zh"),
                new I18nLanguageOption("English", "en"), new I18nLanguageOption("English US", "en-us"),
                new I18nLanguageOption("Español", "es")));
        supplier = mock(TranslationUtils.class);
        when(supplier.translateToMultipleLanguagesParallel(anyString(), anyList(), anyString())).thenAnswer(call -> {
            String language = ((List<String>) call.getArgument(1)).get(0).split("-", 2)[0];
            return Map.of(language, "html".equals(call.getArgument(2)) ? "<p>Hello {name}</p>" : "Hello");
        });
        work = new ConcurrentLinkedQueue<>();
        var manager = new DataSourceTransactionManager(source);
        translation = new ComputeContentTranslationService(jdbc, data, scope, locales, supplier, work::add, manager);
        content = new ComputeContentService(jdbc, data, scope, locales, work::add, manager, translation);
    }

    private Map<String,Object> input() {
        return new LinkedHashMap<>(Map.of("kind","ARTICLE","title","中文标题","bodyHtml","<p>你好 {name}</p>","enabled",true,
                "translations",Map.of("en",Map.of("title","English source","bodyHtml","<p>English body {name}</p>"))));
    }
    private Map<String,Object> form() {
        return new LinkedHashMap<>(Map.of("title","中文标题","bodyHtml","<p>你好 {name}</p>","sourceLanguage","zh"));
    }
    private long create(Map<String,Object> body) {
        long id=((Number)content.save(body,1).get("id")).longValue();
        jdbc.update("UPDATE compute_content SET status='PUBLISHED' WHERE id=?",id);return id;
    }
    private void runAll(){Runnable job;while((job=work.poll())!=null)job.run();}
    private long taskId(Map<String,Object> task){return ((Number)task.get("taskId")).longValue();}

    @Test void activityDraftTranslatesTitleDescriptionAndContentWithoutOverwritingOtherLanguages() {
        Map<String,Object> form=new LinkedHashMap<>(Map.of("type","CHEST","title","宝箱标题","bodyHtml","<p>宝箱说明</p>","description","宝箱描述", "sourceLanguage","zh","targetLanguages",List.of("en"),
                "translations",Map.of("es",Map.of("title","Titulo manual","bodyHtml","<p>Manual</p>","description","Descripcion manual"))));
        long task=taskId(translation.submitForm("ACTIVITY",form,1));runAll();
        Map<String,Object> result=translation.formTask("ACTIVITY",task,1);
        assertEquals("SUCCEEDED",result.get("status"));assertEquals("CHEST",translation.formActivityType(task,1));
        Map<?,?> all=(Map<?,?>)result.get("translations"),english=(Map<?,?>)all.get("en"),spanish=(Map<?,?>)all.get("es");
        assertEquals("Hello",english.get("title"));assertEquals("Hello",english.get("description"));assertEquals("<p>Hello {name}</p>",english.get("bodyHtml"));
        assertEquals("Descripcion manual",spanish.get("description"));assertThrows(BusinessException.class,()->translation.formTask("ACTIVITY",task,2));
        form.put("description","新的宝箱描述");assertNotEquals(task,taskId(translation.submitForm("ACTIVITY",form,1)));
    }

    @Test void contentDraftKindIsPersistedAndSeparatesOtherwiseIdenticalSnapshots() {
        var article=form();article.put("kind","ARTICLE");var message=form();message.put("kind","MESSAGE");
        long first=taskId(translation.submitForm("CONTENT",article,1));
        long second=taskId(translation.submitForm("CONTENT",message,1));
        assertNotEquals(first,second);
        assertEquals("ARTICLE",translation.formContentKind(first,1));
        assertEquals("MESSAGE",translation.formContentKind(second,1));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_translation_form",Integer.class));
        assertThrows(BusinessException.class,()->translation.formContentKind(first,2));
    }

    @Test void savedContentKindWinsAndUnknownHistoricDraftRemainsUnclassified() {
        long id=create(input());var saved=form();saved.put("sourceId",id);saved.put("kind","MESSAGE");
        long task=taskId(translation.submitForm("CONTENT",saved,1));
        assertEquals("ARTICLE",translation.formContentKind(task,1));
        jdbc.update("UPDATE compute_translation_form SET content_kind=NULL WHERE source_business_id=?",id);
        assertEquals("ARTICLE",translation.formContentKind(task,1));
        long unknown=taskId(translation.submitForm("CONTENT",form(),1));
        assertEquals("",translation.formContentKind(unknown,1));
    }

    @Test void savingWithoutEnglishRequiresFormTranslationAndNeverAutomaticallyQueues() {
        var body=input();body.remove("translations");
        assertThrows(BusinessException.class,()->content.save(body,1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_translation_task",Integer.class));
        verifyNoInteractions(supplier);
    }

    @Test void completeChineseAndMissingEnglishHistoricalRowsDoNotTriggerTranslation() {
        long id=create(input());
        jdbc.update("UPDATE compute_content SET translations_json='{}' WHERE id=?",id);
        assertEquals("中文标题",content.userDetail(id,null,"zh").get("title"));
        var missing=content.userDetail(id,null,"es");
        assertEquals("FAILED",missing.get("translationStatus"));assertEquals("",missing.get("bodyHtml"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_translation_task",Integer.class));verifyNoInteractions(supplier);
    }

    @Test void requestedLanguageImmediatelyFallsBackToEnglishThenPersistsOnlyThatLanguage() throws Exception {
        long id=create(input());var pending=content.userDetail(id,null,"es");
        assertEquals("READY",pending.get("translationStatus"));assertEquals("PENDING",pending.get("translationPendingStatus"));
        assertEquals("English source",pending.get("title"));assertEquals("en",pending.get("translationLanguage"));
        content.userDetail(id,null,"es");assertTrue(work.isEmpty());translation.dispatchPending();assertEquals(1,work.size());runAll();
        var ready=content.userDetail(id,null,"es");assertEquals("Hello",ready.get("title"));assertEquals("es",ready.get("translationLanguage"));
        verify(supplier).translateToMultipleLanguagesParallel("English source",List.of("es"),"text");
        verify(supplier).translateToMultipleLanguagesParallel("<p>English body {name}</p>",List.of("es"),"html");
        assertEquals(List.of("es"),jdbc.queryForList("SELECT language FROM compute_content_translation_item",String.class));
    }

    @Test void failureFallsBackToEnglishAndCoolingPreventsRepeatedSupplierCalls() throws Exception {
        doThrow(new BusinessException("server.request.timeout")).when(supplier).translateToMultipleLanguagesParallel(anyString(),anyList(),anyString());
        long id=create(input());content.userDetail(id,null,"es");translation.dispatchPending();runAll();
        for(int index=0;index<5;index++){
            var failed=content.userDetail(id,null,"es");
            assertEquals("English source",failed.get("title"));assertEquals("<p>English body {name}</p>",failed.get("bodyHtml"));
            assertEquals("en",failed.get("translationLanguage"));assertEquals(true,failed.get("translationFallback"));
            assertEquals("FAILED",failed.get("translationPendingStatus"));
        }
        verify(supplier,times(1)).translateToMultipleLanguagesParallel(anyString(),anyList(),anyString());assertTrue(work.isEmpty());
        jdbc.update("UPDATE compute_content_translation_item SET retry_after=TIMESTAMP '2000-01-01 00:00:00'");
        content.userDetail(id,null,"es");translation.dispatchPending();assertEquals(1,work.size());
    }

    @Test void unsavedFormReturnsTranslatedSnapshotWithoutCreatingBusinessContent() {
        var request=form();request.put("targetLanguages",List.of("en"));
        var first=translation.submitForm("CONTENT",request,1);var duplicate=translation.submitForm("CONTENT",request,1);
        assertEquals(first.get("taskId"),duplicate.get("taskId"));assertEquals(1,work.size());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content",Integer.class));runAll();
        var result=translation.formTask("CONTENT",taskId(first),1);
        assertEquals("SUCCEEDED",result.get("status"));assertEquals("Hello",((Map<?,?>)((Map<?,?>)result.get("translations")).get("en")).get("title"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content",Integer.class));
        var saved=input();saved.put("translations",result.get("translations"));assertTrue((Boolean)content.save(saved,1).get("englishReady"));
        assertThrows(BusinessException.class,()->translation.formTask("CONTENT",taskId(first),2));
    }

    @Test void explicitEnglishAndAllRequestsRefreshTheirTargetsInSeparateSnapshots() throws Exception {
        var request=form();request.put("translations",Map.of("en",Map.of("title","Manual English","bodyHtml","")));
        request.put("targetLanguages",List.of("en"));var english=translation.submitForm("CONTENT",request,1);
        request.remove("targetLanguages");var all=translation.submitForm("CONTENT",request,1);
        assertNotEquals(english.get("taskId"),all.get("taskId"));runAll();
        var result=translation.formTask("CONTENT",taskId(all),1);var translations=(Map<?,?>)result.get("translations");
        assertEquals("Hello",((Map<?,?>)translations.get("en")).get("title"));assertTrue(translations.containsKey("es"));
        verify(supplier,times(2)).translateToMultipleLanguagesParallel("中文标题",List.of("en"),"text");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content",Integer.class));
    }

    @Test void freshFormTranslationAlwaysUsesEditedSourceDespiteCompleteHistoricalTranslations() throws Exception {
        for(String resource:List.of("CONTENT","PRODUCT","LEVEL")) {
            var request=form();request.put("title","最新中文"+resource);request.put("bodyHtml","<p>最新说明"+resource+"</p>");
            request.put("translations",Map.of("en",Map.of("title","Old English","bodyHtml","<p>Old English body</p>"),
                    "es",Map.of("title","Old Spanish","bodyHtml","<p>Old Spanish body</p>")));
            var task=translation.submitForm(resource,request,1);runAll();
            var result=translation.formTask(resource,taskId(task),1);var values=(Map<?,?>)result.get("translations");
            assertEquals("SUCCEEDED",result.get("status"));
            assertEquals("Hello",((Map<?,?>)values.get("en")).get("title"));
            assertEquals("Hello",((Map<?,?>)values.get("es")).get("title"));
            assertEquals("最新中文"+resource,((Map<?,?>)values.get("zh")).get("title"));
            verify(supplier).translateToMultipleLanguagesParallel("最新中文"+resource,List.of("en"),"text");
            verify(supplier).translateToMultipleLanguagesParallel("<p>最新说明"+resource+"</p>",List.of("es"),"html");
        }
        verify(supplier,never()).translateToMultipleLanguagesParallel(eq("Old English"),anyList(),anyString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content",Integer.class));
    }

    @Test void selectedFormTranslationKeepsNonTargetsAndNeverWritesSavedBusinessContent() throws Exception {
        long id=create(input());var request=form();request.put("sourceId",id);request.put("title","当前中文");
        request.put("targetLanguages",List.of("es"));
        request.put("translations",Map.of("en",Map.of("title","Historical English","bodyHtml","<p>Historical English body</p>"),
                "es",Map.of("title","Historical Spanish","bodyHtml","<p>Historical Spanish body</p>")));
        var task=translation.submitForm("CONTENT",request,1);runAll();
        var values=(Map<?,?>)translation.formTask("CONTENT",taskId(task),1).get("translations");
        assertEquals("Historical English",((Map<?,?>)values.get("en")).get("title"));
        assertEquals("Hello",((Map<?,?>)values.get("es")).get("title"));
        verify(supplier).translateToMultipleLanguagesParallel("当前中文",List.of("es"),"text");
        verify(supplier,never()).translateToMultipleLanguagesParallel(anyString(),eq(List.of("en")),anyString());
        var saved=content.adminDetail(id);assertEquals("中文标题",saved.get("title"));
        assertFalse(((Map<?,?>)saved.get("translations")).containsKey("es"));
    }

    @Test void savedContentCompletionStillPreservesExistingManualTranslation() throws Exception {
        var request=input();request.put("translations",Map.of(
                "en",Map.of("title","English source","bodyHtml","<p>English body</p>"),
                "es",Map.of("title","Manual Spanish","bodyHtml","<p>Manual Spanish body</p>")));
        long id=create(request);translation.submit(id,List.of("es"),1);runAll();
        assertEquals("Manual Spanish",content.userDetail(id,null,"es").get("title"));
        verifyNoInteractions(supplier);
    }

    @Test void changedFormUsesIndependentSnapshotAndCannotOverwriteSavedRecord() {
        long id=create(input());var old=form();old.put("targetLanguages",List.of("en"));
        var first=translation.submitForm("CONTENT",old,1);
        var changed=form();changed.put("title","修订标题");changed.put("targetLanguages",List.of("en"));
        var second=translation.submitForm("CONTENT",changed,1);assertNotEquals(first.get("taskId"),second.get("taskId"));runAll();
        assertEquals("中文标题",content.adminDetail(id).get("title"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_translation_form",Integer.class));
        assertEquals("SUCCEEDED",translation.formTask("CONTENT",taskId(first),1).get("status"));
        assertEquals("SUCCEEDED",translation.formTask("CONTENT",taskId(second),1).get("status"));
    }

    @Test void concurrentManualTargetFieldsAreNeverReplaced() throws Exception {
        var body=input();body.put("translations",Map.of("en",Map.of("title","English source","bodyHtml","<p>English body {name}</p>"),"es",Map.of("title","Manual title","bodyHtml","")));
        long id=create(body);content.userDetail(id,null,"es");translation.dispatchPending();
        doAnswer(call->{var edit=input();edit.put("id",id);edit.put("translations",Map.of("es",Map.of("title","","bodyHtml","<p>Manual body</p>")));content.save(edit,1);return Map.of("es","<p>Machine body</p>");})
                .when(supplier).translateToMultipleLanguagesParallel(anyString(),anyList(),eq("html"));
        runAll();var ready=content.userDetail(id,null,"es");assertEquals("Manual title",ready.get("title"));assertEquals("<p>Manual body</p>",ready.get("bodyHtml"));
        verify(supplier,never()).translateToMultipleLanguagesParallel(anyString(),anyList(),eq("text"));
    }

    @Test void changedEnglishVersionFencesInFlightTargetAndUsesNewSource() throws Exception {
        long id=create(input());var pending=content.userDetail(id,null,"es");translation.dispatchPending();
        doAnswer(call->{var edit=input();edit.put("id",id);edit.put("translations",Map.of("en",Map.of("title","Revised English","bodyHtml","<p>Revised body</p>")));content.save(edit,1);return Map.of("es","Stale title");})
                .when(supplier).translateToMultipleLanguagesParallel(eq("English source"),anyList(),eq("text"));
        runAll();assertEquals("SUPERSEDED",translation.task(id,((Number)pending.get("translationTaskId")).longValue()).get("status"));
        assertFalse(((Map<?,?>)content.adminDetail(id).get("translations")).containsKey("es"));
        var next=content.userDetail(id,null,"es");assertEquals("Revised English",next.get("title"));translation.dispatchPending();runAll();
        verify(supplier).translateToMultipleLanguagesParallel("Revised English",List.of("es"),"text");
    }

    @Test void concurrentReadsCreateOneDurableItemAndOneSupplierJob() throws Exception {
        long id=create(input());ExecutorService readers=Executors.newFixedThreadPool(4);
        try{var calls=new ArrayList<Callable<Map<String,Object>>>();for(int index=0;index<8;index++)calls.add(()->content.userDetail(id,null,"es"));
            for(var result:readers.invokeAll(calls))assertEquals("English source",result.get(15,TimeUnit.SECONDS).get("title"));
        }finally{readers.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_translation_item",Integer.class));translation.dispatchPending();assertEquals(1,work.size());runAll();
        verify(supplier,times(2)).translateToMultipleLanguagesParallel(anyString(),anyList(),anyString());
    }

    @Test void regionalLanguageIsNormalizedWithoutAddingEnglishOrOtherTargets() {
        long id=create(input());content.userDetail(id,null,"EN_US");translation.dispatchPending();runAll();
        assertEquals("en-us",content.userDetail(id,null,"en-us").get("translationLanguage"));
        assertEquals(List.of("en-us"),jdbc.queryForList("SELECT language FROM compute_content_translation_item",String.class));
    }

    @Test void catalogTranslationFailureUsesEnglishAndDoesNotLeakChineseSource() throws Exception {
        doThrow(new BusinessException("server.translate.failed")).when(supplier).translateToMultipleLanguagesParallel(anyString(),anyList(),anyString());
        for (String resource : List.of("PRODUCT", "LEVEL")) {
            String table="compute_"+resource.toLowerCase(Locale.ROOT);
            jdbc.execute("CREATE TABLE "+table+"(id BIGINT PRIMARY KEY,name VARCHAR(200),description TEXT,source_language VARCHAR(20),translations_json TEXT,content_version BIGINT)");
            jdbc.update("INSERT INTO "+table+" VALUES(1,?,?,?, ?,1)","中文名称","<p>中文说明</p>","zh",data.json(Map.of("en",Map.of("title","English name","bodyHtml","<p>English description</p>"))));
            var first=data.one("SELECT * FROM "+table+" WHERE id=1");translation.applyCatalogTranslation(resource,first,"es");
            assertEquals("English name",first.get("name"));assertEquals("en",first.get("translationLanguage"));
            translation.dispatchPending();runAll();
            var failed=data.one("SELECT * FROM "+table+" WHERE id=1");translation.applyCatalogTranslation(resource,failed,"es");
            assertEquals("English name",failed.get("name"));assertEquals("<p>English description</p>",failed.get("description"));
            assertEquals("FAILED",failed.get("translationPendingStatus"));assertEquals("en",failed.get("translationLanguage"));
            jdbc.update("UPDATE "+table+" SET translations_json='{}' WHERE id=1");
            var missingEnglish=data.one("SELECT * FROM "+table+" WHERE id=1");translation.applyCatalogTranslation(resource,missingEnglish,"es");
            assertEquals("",missingEnglish.get("name"));assertEquals("",missingEnglish.get("description"));
        }
    }

    @Test void expiredLeaseFencesLateWorkerAndAllowsExplicitRetry() throws Exception {
        long id=create(input());var first=translation.submit(id,List.of("es"),1);
        jdbc.update("UPDATE compute_content_translation_item SET lease_until=TIMESTAMP '2000-01-01 00:00:00'");translation.dispatchPending();runAll();
        verifyNoInteractions(supplier);assertEquals("FAILED",translation.task(id,taskId(first)).get("status"));
        translation.submit(id,List.of("es"),1);runAll();assertEquals("es",content.userDetail(id,null,"es").get("translationLanguage"));
    }

    @Test void tasksEnforceScopeEnabledLanguagesAndFormResourceOwnership() {
        long id=create(input());assertThrows(BusinessException.class,()->translation.submit(id,List.of("fr"),1));
        var request=form();request.put("targetLanguages",List.of("fr"));assertThrows(BusinessException.class,()->translation.submitForm("CONTENT",request,1));
        var task=translation.submit(id,List.of("es"),1);when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of()));
        assertThrows(BusinessException.class,()->translation.task(id,taskId(task)));assertThrows(BusinessException.class,()->translation.submitSavedForm("CONTENT",id,List.of("en"),1));
        request.put("targetLanguages",List.of("en"));var formTask=translation.submitForm("CONTENT",request,1);
        assertThrows(BusinessException.class,()->translation.formTask("PRODUCT",taskId(formTask),1));
    }

    @Test void englishCannotBeClearedAndUnsupportedLanguagesUseEnglishWithoutQueueing() {
        long id=create(input());var edit=input();edit.put("id",id);edit.put("clearTranslationLanguages",List.of("en"));
        assertThrows(BusinessException.class,()->content.save(edit,1));assertEquals("English source",content.userDetail(id,null,"fr").get("title"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_content_translation_task",Integer.class));
    }

    @Test void savedRecordTranslationEndpointOnlyWritesFormSnapshot() {
        long id=create(input());var task=translation.submitSavedForm("CONTENT",id,List.of("es"),1);runAll();
        assertEquals("SUCCEEDED",translation.formTask("CONTENT",taskId(task),1).get("status"));
        assertFalse(((Map<?,?>)content.adminDetail(id).get("translations")).containsKey("es"));
    }

    @Test void savedFormTaskRechecksCurrentScopeAndMustMatchItsSourcePath() {
        long first=create(input()),second=create(input());
        var task=translation.submitSavedForm("CONTENT",first,List.of("es"),1);
        assertThrows(BusinessException.class,()->translation.formTask("CONTENT",taskId(task),1,second));
        assertEquals(task.get("taskId"),translation.formTask("CONTENT",taskId(task),1,first).get("taskId"));
        jdbc.update("INSERT INTO compute_member(id,username,password_hash,invite_code,created_at,updated_at) VALUES(1,'translation-one','unused','translation-one',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'translation-two','unused','translation-two',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE compute_content SET owner_member_id=1 WHERE id=?",first);
        jdbc.update("UPDATE compute_content SET owner_member_id=2 WHERE id=?",second);
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(2L)));
        assertEquals(second,((Number)content.adminDetail(second).get("id")).longValue());
        assertThrows(BusinessException.class,()->translation.formTask("CONTENT",taskId(task),1));
        assertThrows(BusinessException.class,()->translation.formTask("CONTENT",taskId(task),1,first));
        var linked=form();linked.put("sourceId",first);linked.put("targetLanguages",List.of("en"));
        assertThrows(BusinessException.class,()->translation.submitForm("CONTENT",linked,1));
        linked.remove("sourceId");assertNotNull(translation.submitForm("CONTENT",linked,1).get("taskId"));
    }

    @Test void productAndLevelFormsSharePipelineButRemainSeparateFromBusinessRows() throws Exception {
        TokenTestSchema.create(jdbc,"compute_product","compute_level","compute_model","compute_currency","compute_fee_rule","system_setting");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('summaryCurrency','USDT')");
        TokenTestSchema.apply(jdbc);TokenTestSchema.content(jdbc);
        jdbc.update("INSERT INTO compute_provider(id,code,name,enabled) VALUES(1,'TEST','测试供应方',1)");
        jdbc.update("INSERT INTO compute_model(id,provider_id,name,input_price,output_price,enabled,updated_at) VALUES(1,1,'测试模型',1,2,1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_level(id,name,threshold,enabled,auto_provider_ids) VALUES(1,'基础会员',0,1,'[]')");
        var currencies=mock(ComputeCurrencyService.class);when(currencies.defaultCurrency()).thenReturn("USDT");when(currencies.hasActiveCurrency("USDT")).thenReturn(true);
        var catalog=new ComputeCatalogService(data,mock(org.token.util.PasswordUtils.class),currencies,translation,locales);
        var body=new LinkedHashMap<String,Object>(Map.of("name","中文标题","description","<p>你好 {name}</p>","type","GPU","minAmount","100","maxAmount","10000","minLevel",1,"durationDays",30,"settlementMode","C","enabled",true,"imageUrl","/token/product.png"));
        body.putAll(Map.of("dailyRateMin","5","dailyRateMax","5","providerId",1,"modelIds",List.of(1)));
        assertThrows(BusinessException.class,()->catalog.save("products",body));
        var request=form();request.put("targetLanguages",List.of("en"));
        var productTask=translation.submitForm("PRODUCT",request,1);var levelTask=translation.submitForm("LEVEL",request,1);
        assertNotEquals(productTask.get("taskId"),levelTask.get("taskId"));runAll();
        body.put("translations",translation.formTask("PRODUCT",taskId(productTask),1).get("translations"));var product=catalog.save("products",body);
        assertTrue((Boolean)product.get("englishReady"));assertTrue(ComputeData.enabled(product.get("enabled")));assertEquals("/token/product.png",product.get("imageUrl"));
        var localized=(List<Map<String,Object>>)catalog.catalog("es").get("products");assertEquals("Hello",localized.get(0).get("name"));assertEquals("en",localized.get(0).get("translationLanguage"));
        var home=catalog.homeCatalog("en");assertFalse(home.containsKey("levels"));assertEquals(1,((List<?>)home.get("products")).size());
        var level=new LinkedHashMap<String,Object>(Map.of("name","中文标题","description","<p>你好 {name}</p>","threshold","100","enabled",true,"imageUrl","/token/tier.png"));
        level.put("translations",translation.formTask("LEVEL",taskId(levelTask),1).get("translations"));assertTrue((Boolean)catalog.save("levels",level).get("englishReady"));
        level.put("imageUrl","javascript:alert(1)");assertThrows(BusinessException.class,()->catalog.save("levels",level));
    }
}
