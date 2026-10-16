package org.token.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.dao.NotificationChannelConfigMapper;
import org.token.repository.dao.NotificationTemplateMapper;
import org.token.model.entity.Language;
import org.token.model.entity.NotificationTemplate;
import org.token.model.entity.Translation;
import org.token.model.request.notification.NotificationTemplateLocalizationRequest;
import org.token.model.request.notification.NotificationTemplateSaveRequest;
import org.token.exception.BusinessException;
import org.token.service.LanguageService;
import org.token.service.TranslationService;
import org.token.tools.security.SensitiveConfigCipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationTemplateEnglishRequiredTest {
    private record Fixture(NotificationConfigServiceImpl service, NotificationTemplateMapper templates,
                           TranslationService translations,org.token.repository.system.TranslationRepository translationRepository) { }

    private Fixture fixture(boolean existing) {
        var templates = mock(NotificationTemplateMapper.class);
        var translations = mock(TranslationService.class);
        var translationRepository = mock(org.token.repository.system.TranslationRepository.class);
        var languages = mock(LanguageService.class);
        Language en = new Language(); en.setLang("en");
        Language zh = new Language(); zh.setLang("zh");
        when(languages.listLanguages(true)).thenReturn(List.of(zh, en));
        if (existing) {
            NotificationTemplate record = new NotificationTemplate();
            record.setId(10L); record.setCode("NTF_EXISTING"); record.setName("通知");
            record.setChannelType("INTERNAL"); record.setContentTemplate("原文");
            when(templates.selectById(10L)).thenReturn(record);
            when(templates.updateById(any(NotificationTemplate.class))).thenReturn(1);
        }
        var service = new NotificationConfigServiceImpl(new org.token.repository.system.NotificationChannelConfigRepository(mock(NotificationChannelConfigMapper.class)), new org.token.repository.system.NotificationTemplateRepository(templates), mock(SensitiveConfigCipher.class), new ObjectMapper(), translations, translationRepository, languages, mock(TelegramBotClient.class), new TelegramChannelLockRegistry(), mock(TelegramFailureAlertService.class));
        return new Fixture(service, templates, translations,translationRepository);
    }

    private NotificationTemplateSaveRequest request(Long id, List<NotificationTemplateLocalizationRequest> values) {
        return new NotificationTemplateSaveRequest(id, null, "通知", "INTERNAL", "标题", "<p>原文</p>", 1, values);
    }

    private void existingEnglish(Fixture fixture) {
        Translation title = new Translation(); title.setLang("en"); title.setTvalue("Saved title");
        Translation body = new Translation(); body.setLang("en"); body.setTvalue("<p>Saved body</p>");
        when(fixture.translations.getTranslationBatch("notification.template.ntf_existing.title", 3)).thenReturn(List.of(title));
        when(fixture.translations.getTranslationBatch("notification.template.ntf_existing.content", 3)).thenReturn(List.of(body));
    }

    @Test
    void newTemplateCannotBeSavedWithoutEnglish() {
        var fixture = fixture(false);
        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.service.saveTemplate(request(null, List.of(new NotificationTemplateLocalizationRequest("zh", "标题", "正文")))));
        assertEquals("server.translate.englishRequired", error.getMessage());
        verify(fixture.templates, never()).insert(any(NotificationTemplate.class));
        verifyNoInteractions(fixture.translations);
    }

    @Test
    void explicitEmptyEnglishCannotFallBackToSavedEnglish() {
        var fixture = fixture(true);
        assertThrows(BusinessException.class, () -> fixture.service.saveTemplate(request(10L,
                List.of(new NotificationTemplateLocalizationRequest("en", "Title", "<p>&nbsp;<br></p>")))));
        verify(fixture.templates, never()).updateById(any(NotificationTemplate.class));
        verify(fixture.translationRepository, never()).deleteTemplateLocalizations(any(),any(),any());
    }

    @Test
    void sourceTitleRequiresEnglishTitle() {
        var fixture = fixture(true);
        assertThrows(BusinessException.class, () -> fixture.service.saveTemplate(request(10L,
                List.of(new NotificationTemplateLocalizationRequest("en", " ", "Body")))));
        verify(fixture.templates, never()).updateById(any(NotificationTemplate.class));
    }

    @Test
    void omittedEnglishIsRetainedWhenOtherLanguagesAreReplaced() {
        var fixture = fixture(true); existingEnglish(fixture);
        assertDoesNotThrow(() -> fixture.service.saveTemplate(request(10L,
                List.of(new NotificationTemplateLocalizationRequest("zh", "新标题", "新正文")))));
        ArgumentCaptor<Translation> saved = ArgumentCaptor.forClass(Translation.class);
        verify(fixture.translations, atLeastOnce()).save(saved.capture());
        assertTrue(saved.getAllValues().stream().anyMatch(row -> "en".equals(row.getLang()) && "<p>Saved body</p>".equals(row.getTvalue())));
        assertTrue(saved.getAllValues().stream().anyMatch(row -> "en".equals(row.getLang()) && "Saved title".equals(row.getTvalue())));
    }

    @Test
    void legacyNullTranslationsRetainExistingEnglishWithoutDeletion() {
        var fixture = fixture(true); existingEnglish(fixture);
        assertDoesNotThrow(() -> fixture.service.saveTemplate(request(10L, null)));
        verify(fixture.translationRepository, never()).deleteTemplateLocalizations(any(),any(),any());
        verify(fixture.templates).updateById(any(NotificationTemplate.class));
    }
}
