package org.token.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.token.config.EnabledLocaleResolver;
import org.token.repository.dao.TranslationMapper;
import org.token.model.entity.Translation;
import org.token.model.request.FilterParams;
import org.token.model.request.QueryParams;
import org.token.model.response.AdminI18nBundle;
import org.token.model.response.I18nLanguageOption;
import org.token.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class TranslationServiceImplTest {

    @Mock
    private TranslationMapper translationMapper;

    @Mock
    private EnabledLocaleResolver localeResolver;

    private TranslationServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace("org.exp.test.TranslationMapper");
        TableInfoHelper.initTableInfo(assistant, Translation.class);

        service = new TranslationServiceImpl(localeResolver);
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.TranslationRepository(translationMapper));
    }

    @Test
    void explicitBackendLanguagePackageTypeIsAllowedForReadOnlyListing() {
        when(translationMapper.selectById(5)).thenReturn(template(5));

        assertDoesNotThrow(() -> service.getTranslationPage(queryForType("5")));

        verify(translationMapper).findPage(any(),any(),eq(5),eq(false),anyList());
        String sql=org.token.repository.dao.provider.SystemPageSql.translations(java.util.Map.of("query",queryForType("5"),"selectedType",5,"global",false,"systemKeys",List.of()));
        assertTrue(sql.contains("type=#{selectedType}"));
        assertFalse(sql.contains("type NOT IN (5,7)"));
    }

    @Test
    void titleAndContentFiltersUseIndependentFuzzyColumns() {
        QueryParams query = new QueryParams();
        FilterParams filters = new FilterParams();
        filters.setLangTitle("模板标题");
        filters.setContent("正文片段");
        query.setFilterParams(filters);
        service.getTranslationPage(query);
        verify(translationMapper).findPage(any(),same(query),isNull(),eq(false),anyList());
        String sql=org.token.repository.dao.provider.SystemPageSql.translations(java.util.Map.of("query",query,"global",false,"systemKeys",List.of()));
        assertTrue(sql.contains("title LIKE CONCAT('%',TRIM(#{query.filterParams.langTitle}),'%')"));
        assertTrue(sql.contains("tvalue LIKE CONCAT('%',TRIM(#{query.filterParams.content}),'%')"));
    }

    @Test
    void explicitFrontendLanguagePackageTypeIsAllowedForReadOnlyListing() {
        when(translationMapper.selectById(7)).thenReturn(template(7));

        assertDoesNotThrow(() -> service.getTranslationPage(queryForType("7")));

        verify(translationMapper).findPage(any(),any(),eq(7),eq(false),anyList());
        String sql=org.token.repository.dao.provider.SystemPageSql.translations(java.util.Map.of("query",queryForType("7"),"selectedType",7,"global",false,"systemKeys",List.of()));
        assertTrue(sql.contains("type=#{selectedType}"));
        assertFalse(sql.contains("type NOT IN (5,7)"));
    }

    @Test
    void explicitUnknownTemplateTypeIsRejected() {
        when(translationMapper.selectById(999)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.getTranslationPage(queryForType("999")));
    }

    @Test
    void serverMessageUsesTypeOneAndFallsBackToEnglishWithoutWritingDatabase() {
        when(localeResolver.resolve("es-MX")).thenReturn("es-mx");
        when(localeResolver.lookupCandidates("es-mx")).thenReturn(List.of("es-mx", "es", "en"));
        when(localeResolver.normalize(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(translationMapper.findLanguageCandidates(any(),anyInt(),anyList()))
                .thenReturn(List.of(translation("server.success", "en", "Success", 1)));

        String message = service.getTranslate("server.success", "es-MX");

        assertEquals("Success", message);
        verify(translationMapper).findLanguageCandidates("server.success",1,List.of("es-mx","es","en"));
        verify(translationMapper,never()).findLatestByKeyLanguageType(any(),any(),any());
        verify(translationMapper, never()).insert(any(Translation.class));
        verify(translationMapper, never()).updateById(any(Translation.class));
    }

    @Test void serverMessageKeepsLatestDuplicateAndLanguagePriorityWithoutCaching() {
        when(localeResolver.resolve("es-MX")).thenReturn("es-mx");
        when(localeResolver.lookupCandidates("es-mx")).thenReturn(List.of("es-mx","es","en"));
        when(localeResolver.normalize(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(translationMapper.findLanguageCandidates(any(),anyInt(),anyList())).thenReturn(
                List.of(translation("server.success","es-mx","",1),translation("server.success","es-mx","旧地区译文",1),translation("server.success","en","Success",1),translation("server.success","es","Correcto",1)),
                List.of(translation("server.success","en","Success",1),translation("server.success","es-mx","Actualizado",1)));
        assertEquals("Correcto",service.getTranslate("server.success","es-MX"));
        assertEquals("Actualizado",service.getTranslate("server.success","es-MX"));
        verify(translationMapper,times(2)).findLanguageCandidates("server.success",1,List.of("es-mx","es","en"));
    }

    @Test
    void adminBundleUsesTypeFiveAndFiltersOutNonAdminKeys() {
        when(localeResolver.resolve("es-MX")).thenReturn("es");
        when(localeResolver.lookupCandidates("es")).thenReturn(List.of("es", "en"));
        when(localeResolver.enabledLanguages()).thenReturn(List.of(
                new I18nLanguageOption("中文", "zh"),
                new I18nLanguageOption("English", "en")
        ));
        when(translationMapper.findBundle(anyInt(),anyString(),anyString()))
                .thenReturn(
                        List.of(
                                translation("admin.account.title", "en", "Accounts", 5),
                                translation("server.success", "en", "Success", 5)
                        ),
                        List.of()
                );

        AdminI18nBundle bundle = service.getAdminI18nBundle("es-MX");

        assertEquals("es", bundle.locale());
        assertEquals("Accounts", bundle.messages().get("admin.account.title"));
        assertNull(bundle.messages().get("server.success"));
        verify(translationMapper).findBundle(5,"en","admin.");
        verify(translationMapper).findBundle(5,"es","admin.");
    }

    @Test void bodyCacheHitsAllThreeTypesAndPreservesTheirDifferentBlankFallbackRules() {
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL("jdbc:h2:mem:service-translation-cache-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.execute("CREATE TABLE system_setting(setting_key VARCHAR(100) PRIMARY KEY,setting_value VARCHAR(200),updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES(?,?)",org.token.tools.cache.TranslationBodyCache.REVISION_KEY,java.util.UUID.randomUUID().toString());
        var policies = org.mockito.Mockito.mock(org.token.tools.cache.CacheRuntimePolicyService.class);
        when(policies.current()).thenReturn(new org.token.tools.cache.CachePolicySnapshot(true,org.token.tools.cache.CacheMode.MEMORY,java.util.Map.of()));
        var hybrid = new org.token.tools.cache.HybridCacheService(org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),policies,"test");
        var cache = new org.token.tools.cache.TranslationBodyCache(hybrid,jdbc,new com.fasterxml.jackson.databind.ObjectMapper(),new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        service = new TranslationServiceImpl(localeResolver, null, null, cache);
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.TranslationRepository(translationMapper));
        when(localeResolver.resolve("es-MX")).thenReturn("es-mx");
        when(localeResolver.lookupCandidates("es-mx")).thenReturn(List.of("es-mx","es","en"));
        when(localeResolver.normalize(any())).thenAnswer(call->call.getArgument(0));
        when(localeResolver.enabledLanguages()).thenReturn(List.of(new I18nLanguageOption("English","en")));
        when(translationMapper.findLanguageCandidates(any(),anyInt(),anyList())).thenReturn(List.of(translation("server.success","es-mx","",1),translation("server.success","es-mx","旧地区",1),translation("server.success","es","Correcto",1)));
        when(translationMapper.findBundle(anyInt(),anyString(),anyString())).thenAnswer(call->{
            int type=call.getArgument(0);String lang=call.getArgument(1),prefix=call.getArgument(2);
            if("en".equals(lang))return List.of(translation(prefix+"word","en","Preserved",type),translation(prefix+"word","en","",type));
            return List.of();
        });
        for(int i=0;i<2;i++) {
            assertEquals("Correcto",service.getTranslate("server.success","es-MX"));
            assertEquals("Preserved",service.getAdminI18nBundle("es-MX").messages().get("admin.word"));
            assertEquals("Preserved",service.getUserI18nMessages("es-MX").get("user.word"));
        }
        verify(translationMapper,times(1)).findLanguageCandidates(any(),eq(1),anyList());
        verify(translationMapper,times(6)).findBundle(anyInt(),anyString(),anyString());
    }

    private QueryParams queryForType(String type) {
        FilterParams filter = new FilterParams();
        filter.setType(type);
        QueryParams params = new QueryParams();
        params.setFilterParams(filter);
        return params;
    }

    private Translation template(int id) {
        Translation template = new Translation();
        template.setId(id);
        template.setType(0);
        template.setTkey("template-" + id);
        return template;
    }

    private Translation translation(String key, String lang, String value, int type) {
        Translation translation = new Translation();
        translation.setTkey(key);
        translation.setLang(lang);
        translation.setTvalue(value);
        translation.setType(type);
        return translation;
    }

}
