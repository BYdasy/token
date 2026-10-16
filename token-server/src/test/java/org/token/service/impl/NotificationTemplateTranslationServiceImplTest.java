package org.token.service.impl;

import org.token.model.request.notification.NotificationTemplateTranslateRequest;
import org.token.model.response.NotificationTemplateTranslationResult;
import org.token.service.LanguageService;
import org.token.tools.translation.TranslationUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationTemplateTranslationServiceImplTest {

    @Test
    void telegramTemplateUsesDedicatedFormatAndAllEnabledLanguages() throws Exception {
        LanguageService languageService = mock(LanguageService.class);
        TranslationUtils translationUtils = mock(TranslationUtils.class);
        when(languageService.enabledLanguageCodes()).thenReturn(List.of("zh", "en", "ja"));
        when(translationUtils.translateToMultipleLanguagesParallel(
                "通知标题", List.of("en", "ja"), "text"))
                .thenReturn(Map.of("en", "Title", "ja", "タイトル"));
        when(translationUtils.translateToMultipleLanguagesParallel(
                "请复制 `EXP-001`", List.of("en", "ja"), "telegram"))
                .thenReturn(Map.of(
                        "en", "Copy <code>EXP-001</code>",
                        "ja", "コピー <code>EXP-001</code>"));

        NotificationTemplateTranslationServiceImpl service =
                new NotificationTemplateTranslationServiceImpl(languageService, translationUtils);
        NotificationTemplateTranslationResult result = service.translate(
                new NotificationTemplateTranslateRequest(
                        "TELEGRAM", "zh", "通知标题", "请复制 `EXP-001`", null));

        assertEquals(List.of("en", "ja"), result.translations().stream()
                .map(item -> item.lang()).toList());
        assertEquals("Copy <code>EXP-001</code>", result.translations().get(0).content());
        verify(translationUtils).translateToMultipleLanguagesParallel(
                "请复制 `EXP-001`", List.of("en", "ja"), "telegram");
    }
}
