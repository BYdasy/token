package org.token.controller.admin;

import org.token.model.entity.Translation;
import org.token.model.request.FilterParams;
import org.token.model.request.QueryParams;
import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.token.service.LanguageService;
import org.token.service.TranslationCompletionService;
import org.token.service.TranslationCompletionTaskService;
import org.token.service.TranslationService;
import org.token.model.security.JwtUserPrincipal;
import org.token.tools.translation.TranslationUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationControllerTest {

    private static final String TRANSLATION_READ_PERMISSION =
            "hasAnyAuthority('606','60602','6060201','60603','6060301','60604','6060401','super')";

    private static final String TEMPLATE_CREATE_PERMISSION =
            "hasAnyAuthority('606','60601','6060102','super')";

    private static final String TEMPLATE_COMPLETION_PERMISSION =
            "hasAnyAuthority('606','60601','6060105','super')";

    private static final String TEMPLATE_READ_PERMISSION =
            "hasAnyAuthority('606','60601','6060101','60602','6060201','60603','6060301','60604','6060401','60605','6060501','super')";

    private static final String TRANSLATION_PREVIEW_PERMISSION =
            "hasAnyAuthority('606','60602','6060205','60603','6060303','60604','6060403','super')";

    private static final String TRANSLATION_CREATE_PERMISSION =
            "hasAnyAuthority('606','60602','6060202','60603','6060302','60604','6060402','super')";

    private static final String TRANSLATION_UPDATE_PERMISSION =
            "hasAnyAuthority('606','60602','6060203','60603','6060303','60604','6060403','super')";

    @Mock
    private ResultFactory resultFactory;
    @Mock
    private TranslationService translationService;
    @Mock
    private LanguageService languageService;
    @Mock
    private TranslationCompletionService translationCompletionService;
    @Mock
    private TranslationCompletionTaskService translationCompletionTaskService;
    @Mock
    private TranslationUtils translationUtils;
    @Mock
    private org.token.service.compute.ComputeSystemMessageService systemMessages;
    @Mock
    private org.token.service.compute.ComputeAgentScope computeScope;

    private TranslationController controller;

    @BeforeEach
    void setUp() {
        controller = new TranslationController(new org.token.service.system.TranslationManagementService(translationService, org.mockito.Mockito.mock(org.token.repository.system.TranslationRepository.class), languageService, translationCompletionService, translationCompletionTaskService, translationUtils, systemMessages, computeScope), resultFactory);
    }

    @Test void withdrawalPersonalMessageKeysCannotBeDeletedAsOrdinaryTranslations() {
        for (String key: List.of("user.systemMessage.withdrawal_completed.title","user.systemMessage.withdrawal_completed.body")) {
            Translation request=new Translation();request.setTkey(key);request.setType(6);
            assertEquals("server.v7.translation.systemTemplateProtected",assertThrows(BusinessException.class,()->controller.removeTranslation(request)).getMessage());
        }
        org.mockito.Mockito.verifyNoInteractions(translationService);
    }

    @Test
    void backendLanguagePackageAllowsBatchAdd() {
        Translation template = new Translation();
        template.setId(5);
        template.setType(0);
        when(translationService.getById(5)).thenReturn(template);

        Translation request = new Translation();
        request.setTkey("admin.title");
        request.setTvalue("管理后台");
        request.setType(5);
        request.setLanguages(List.of("en"));
        request.setTranslations(Map.of("en", "Admin"));
        when(translationService.isExist("admin.title", "zh", 5)).thenReturn(false);

        assertDoesNotThrow(() -> controller.translateAndAddBatch(request));
        verify(translationService).saveBatch(org.mockito.ArgumentMatchers.argThat(
                records -> records.size() == 2
                        && records.stream().allMatch(item -> Integer.valueOf(5).equals(item.getType()))
        ));
    }

    @Test
    void languagePackagesCanNeverBeDeleted() {
        Translation backendTemplate = new Translation();
        backendTemplate.setId(5);
        backendTemplate.setType(0);
        Translation template = new Translation();
        template.setId(7);
        template.setType(0);
        when(translationService.getById(5)).thenReturn(backendTemplate);
        when(translationService.getById(7)).thenReturn(template);

        Translation backendRequest = new Translation();
        backendRequest.setTkey("admin.title");
        backendRequest.setType(5);
        Translation frontendRequest = new Translation();
        frontendRequest.setTkey("home.title");
        frontendRequest.setType(7);

        assertThrows(BusinessException.class, () -> controller.removeTranslation(backendRequest));
        assertThrows(BusinessException.class, () -> controller.removeTranslation(frontendRequest));
        verify(translationService, never()).deleteTranslation(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void frontendLanguagePackageCanUseTranslationPreview() throws Exception {
        Translation template = new Translation();
        template.setId(7);
        template.setType(0);
        when(translationService.getById(7)).thenReturn(template);

        Translation request = new Translation();
        request.setType(7);
        request.setTvalue("首页");
        request.setLanguages(List.of("en"));
        when(translationUtils.translateToMultipleLanguagesParallel("首页", List.of("en"), "text"))
                .thenReturn(Map.of("en", "Home"));

        assertDoesNotThrow(() -> controller.translateToAllLanguages(request));
        verify(translationUtils).translateToMultipleLanguagesParallel("首页", List.of("en"), "text");
    }

    @Test
    void languagePackageListDelegatesAsReadOnlyQuery() {
        FilterParams filter = new FilterParams();
        filter.setType("5");
        QueryParams params = new QueryParams();
        params.setFilterParams(filter);

        controller.getTranslation(params);

        verify(translationService).getTranslationPage(params);
    }

    @Test
    void translationEndpointsUseExplicitAuthorityCodes() throws NoSuchMethodException {
        assertEquals(TEMPLATE_READ_PERMISSION, permissionOf("getTem"));
        assertEquals(TEMPLATE_CREATE_PERMISSION, permissionOf("addTem", Translation.class));
        assertEquals(TEMPLATE_COMPLETION_PERMISSION, permissionOf("templateCompletionOverview", org.token.model.request.translation.TemplateCompletionRequest.class));
        assertEquals(TEMPLATE_COMPLETION_PERMISSION, permissionOf("fillMissingTemplateLanguages",
                org.token.model.request.translation.TemplateCompletionRequest.class, JwtUserPrincipal.class));
        assertEquals(TEMPLATE_COMPLETION_PERMISSION, permissionOf("templateCompletionTask",
                Long.class, JwtUserPrincipal.class));

        assertEquals(TRANSLATION_READ_PERMISSION, permissionOf("getTranslation", QueryParams.class));
        assertEquals(TRANSLATION_READ_PERMISSION, permissionOf("getByKeyBatch", Translation.class));
        assertEquals(TRANSLATION_PREVIEW_PERMISSION, permissionOf("translateToAllLanguages", Translation.class));
        assertEquals(TRANSLATION_CREATE_PERMISSION, permissionOf("translateAndAddBatch", Translation.class));
        assertEquals(TRANSLATION_UPDATE_PERMISSION, permissionOf("updateTranslationBatch", Translation.class));
    }

    @Test
    void protectedLanguagePackagesStayOutsideGenericTranslationPage() {
        Translation backendTemplate = new Translation();
        backendTemplate.setId(5);
        backendTemplate.setType(0);
        Translation frontendTemplate = new Translation();
        frontendTemplate.setId(7);
        frontendTemplate.setType(0);
        when(translationService.getTranslation(0)).thenReturn(List.of(backendTemplate, frontendTemplate));

        controller.getTem();

        for (Translation template : List.of(backendTemplate, frontendTemplate)) {
            assertFalse(template.getTranslationEditable());
            assertTrue(template.getProtectedPackage());
            assertFalse(template.getEditable());
        }
    }

    private String permissionOf(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        PreAuthorize annotation = TranslationController.class
                .getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(PreAuthorize.class);
        return annotation.value();
    }

    private Translation englishRequest(int type) {
        Translation template = new Translation(); template.setId(type); template.setType(0);
        when(translationService.getById(type)).thenReturn(template);
        Translation request = new Translation(); request.setTkey("example.english");
        request.setTvalue("中文原文"); request.setType(type); request.setProp(1);
        return request;
    }

    private void savedEnglish(Translation request) {
        Translation english = new Translation(); english.setId(20); english.setTkey(request.getTkey());
        english.setLang("en"); english.setTvalue("Saved English"); english.setType(request.getType());
        when(translationService.getTranslationBatch(request.getTkey(), request.getType())).thenReturn(List.of(english));
    }

    @Test
    void creationForcesEnglishEvenWhenOnlyFrenchWasRequested() throws Exception {
        Translation request = englishRequest(5); request.setLanguages(List.of("fr"));
        when(translationUtils.translateToMultipleLanguagesParallel("中文原文", List.of("fr", "en"), "html"))
                .thenReturn(Map.of("fr", "Français", "en", "English source"));
        controller.translateAndAddBatch(request);
        verify(translationService).saveBatch(org.mockito.ArgumentMatchers.argThat(records ->
                records.stream().anyMatch(row -> "en".equals(row.getLang()) && "English source".equals(row.getTvalue()))));
    }

    @Test
    void failedEnglishGenerationCannotSaveChineseOnly() throws Exception {
        Translation request = englishRequest(5); request.setLanguages(List.of("fr"));
        when(translationUtils.translateToMultipleLanguagesParallel("中文原文", List.of("fr", "en"), "html"))
                .thenReturn(Map.of("fr", "Français"));
        assertThrows(BusinessException.class, () -> controller.translateAndAddBatch(request));
        verify(translationService, never()).saveBatch(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void creationTreatsEmptyEnglishEditorMarkupAsMissingAndGeneratesValidEnglish() throws Exception {
        Translation request = englishRequest(7); request.setTranslations(Map.of("en", "<p>&nbsp;</p>"));
        when(translationUtils.translateToMultipleLanguagesParallel("中文原文", List.of("en"), "html"))
                .thenReturn(Map.of("en", "<p>English content</p>"));
        controller.translateAndAddBatch(request);
        verify(translationService).saveBatch(org.mockito.ArgumentMatchers.argThat(records ->
                records.stream().anyMatch(row -> "en".equals(row.getLang()) && "<p>English content</p>".equals(row.getTvalue()))));
    }

    @Test
    void emptyEditorMarkupAndFailedEnglishGenerationStillCannotSave() throws Exception {
        Translation request = englishRequest(7); request.setTranslations(Map.of("en", "<p>&nbsp;</p>"));
        when(translationUtils.translateToMultipleLanguagesParallel("中文原文", List.of("en"), "html"))
                .thenReturn(Map.of("en", "<p><br></p>"));
        assertEquals("server.translate.englishRequired", assertThrows(BusinessException.class,
                () -> controller.translateAndAddBatch(request)).getMessage());
        verify(translationService, never()).saveBatch(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void previewReportsProviderFailureForMissingOrEmptyRequestedLanguage() throws Exception {
        Translation request = englishRequest(7); request.setLanguages(List.of("en", "fr"));
        when(translationUtils.translateToMultipleLanguagesParallel("中文原文", List.of("en", "fr"), "html"))
                .thenReturn(Map.of("fr", "<p>Français</p>"), Map.of("en", "<p>&nbsp;</p>", "fr", "<p>Français</p>"));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals("server.translate.failed", assertThrows(BusinessException.class,
                    () -> controller.translateToAllLanguages(request)).getMessage());
        }
        verify(translationService, never()).saveBatch(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void previewReturnsRegionalLanguageKeysWithoutCollapsingIntoTheBaseLanguage() throws Exception {
        Translation request = englishRequest(7); request.setLanguages(List.of("en", "pt_BR", "zh-TW"));
        Map<String,String> translations=Map.of("en","English", "pt-br","Português", "zh-tw","繁體中文");
        when(translationUtils.translateToMultipleLanguagesParallel("中文原文", List.of("en", "pt-br", "zh-tw"), "html"))
                .thenReturn(translations);
        controller.translateToAllLanguages(request);
        verify(resultFactory).create(0, translations, "server.success");
    }

    @Test
    void updateRejectsExplicitBlankEnglishWithoutChangingOtherLanguages() {
        Translation request = englishRequest(5); savedEnglish(request);
        request.setTranslations(Map.of("en", "\u200b", "fr", "Français"));
        assertThrows(BusinessException.class, () -> controller.updateTranslationBatch(request));
        verify(translationService, never()).save(org.mockito.ArgumentMatchers.any(Translation.class));
        verify(translationService, never()).updateById(org.mockito.ArgumentMatchers.any(Translation.class));
    }

    @Test
    void updateOmittingEnglishPreservesSavedEnglish() {
        Translation request = englishRequest(5); savedEnglish(request);
        request.setTranslations(Map.of("fr", "Français"));
        controller.updateTranslationBatch(request);
        verify(translationService, org.mockito.Mockito.times(2)).save(org.mockito.ArgumentMatchers.argThat(row ->
                !"en".equals(row.getLang())));
    }

    @Test
    void updateCannotKeepAnExistingItemWithoutEnglish() {
        Translation request = englishRequest(7);
        Translation chinese = new Translation(); chinese.setLang("zh"); chinese.setTvalue("原文");
        when(translationService.getTranslationBatch(request.getTkey(), 7)).thenReturn(List.of(chinese));
        assertThrows(BusinessException.class, () -> controller.updateTranslationBatch(request));
        verify(translationService, never()).save(org.mockito.ArgumentMatchers.any(Translation.class));
    }

    @Test
    void wholeItemDeletionStillUsesExistingPermissionAndTypeRules() {
        Translation request = englishRequest(2);
        controller.removeTranslation(request);
        verify(translationService).deleteTranslation(request.getTkey(), 2);
    }

    @Test void fixedUserMessageUsesExistingTemplateSixEditorAndCannotBeDeleted() {
        Translation request=englishRequest(6);request.setTkey("user.systemMessage.welcome.body");
        request.setTranslations(Map.of("en","<p>English body</p>"));
        controller.updateTranslationBatch(request);
        verify(computeScope).requireGlobalScope();verify(systemMessages).updateTranslationKey(request);
        verify(translationService,never()).save(org.mockito.ArgumentMatchers.any(Translation.class));
        assertThrows(BusinessException.class,()->controller.removeTranslation(request));
    }

    @Test void restrictedScopeCannotReadOrModifyFixedGlobalUserMessage() {
        Translation request=englishRequest(6);request.setTkey("user.systemMessage.welcome.body");
        org.mockito.Mockito.doThrow(new BusinessException("server.compute.forbidden")).when(computeScope).requireGlobalScope();
        assertThrows(BusinessException.class,()->controller.getByKeyBatch(request));
        assertThrows(BusinessException.class,()->controller.updateTranslationBatch(request));
        org.mockito.Mockito.verifyNoInteractions(systemMessages);
    }
}
