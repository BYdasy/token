package org.token.service.impl;

import org.token.repository.dao.TranslationMapper;
import org.token.model.entity.Translation;
import org.token.model.response.TranslationCompletionOverview;
import org.token.model.response.TranslationCompletionResult;
import org.token.service.LanguageService;
import org.token.tools.translation.TranslationUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.token.model.response.TranslationCompletionProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationCompletionServiceImplTest {

    @Mock
    private TranslationMapper translationMapper;

    @Mock
    private LanguageService languageService;

    @Mock
    private TranslationUtils translationUtils;

    private TranslationCompletionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TranslationCompletionServiceImpl(new org.token.repository.system.TranslationRepository(translationMapper), languageService, translationUtils);
    }

    @Test
    void overviewCountsOnlyEnabledLanguagesAndDistinctKeyLanguagePairs() {
        when(translationMapper.selectById(2)).thenReturn(template(2, "邮件模板"));
        when(languageService.enabledLanguageCodes()).thenReturn(List.of("zh", "en", "es"));
        when(translationMapper.findByTypeLatestFirst(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                translation(11, 2, "mail.subject", "zh", "主题"),
                translation(12, 2, "mail.subject", "en", "Subject"),
                translation(13, 2, "mail.subject", "en", "Duplicate must not inflate"),
                translation(14, 2, "mail.subject", "ur", "Disabled language"),
                translation(21, 2, "mail.body", "zh", "正文"),
                translation(22, 2, "mail.body", "en", "Body"),
                translation(23, 2, "mail.body", "es", "Cuerpo")
        ));

        TranslationCompletionOverview overview = service.overview(2);

        assertEquals(3, overview.enabledLanguageCount());
        assertEquals(2, overview.keyCount());
        assertEquals(6, overview.expectedCount());
        assertEquals(5, overview.existingCount());
        assertEquals(1, overview.missingCount());
        assertEquals(1, overview.completeKeyCount());
        assertEquals(1, overview.incompleteKeyCount());
    }

    @Test
    void fillMissingInsertsOnlyMissingLanguagesAndNeverUpdatesExistingRows() throws Exception {
        Translation zh = translation(11, 2, "mail.subject", "zh", "主题");
        Translation en = translation(12, 2, "mail.subject", "en", "Subject");
        Translation es = translation(13, 2, "mail.subject", "es", "Asunto");

        when(translationMapper.selectById(2)).thenReturn(template(2, "邮件模板"));
        when(languageService.enabledLanguageCodes()).thenReturn(List.of("zh", "en", "es"));
        when(translationMapper.findByTypeLatestFirst(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(zh, en))
                .thenReturn(List.of(zh, en, es));
        when(translationUtils.translateToMultipleLanguagesParallel(
                "主题",
                List.of("es"),
                "text"
        )).thenReturn(Map.of("es", "Asunto"));
        when(translationMapper.insertIfMissing(any(Translation.class))).thenReturn(1);

        List<TranslationCompletionProgress> progress = new ArrayList<>();
        TranslationCompletionResult result = service.fillMissing(2, progress::add);

        assertEquals(1, result.inserted());
        assertEquals(1, result.translatedKeys());
        assertEquals(1, result.missingBefore());
        assertEquals(0, result.missingAfter());
        verify(translationMapper).insertIfMissing(any(Translation.class));
        verify(translationMapper, never()).updateById(any(Translation.class));
        assertEquals(0, progress.get(0).processedKeys());
        assertEquals(1, progress.get(progress.size() - 1).processedKeys());
        assertEquals(1, progress.get(progress.size() - 1).inserted());
    }

    private Translation template(int id, String name) {
        Translation template = new Translation();
        template.setId(id);
        template.setType(0);
        template.setTkey(name);
        template.setLang("zh");
        return template;
    }

    private Translation translation(int id, int type, String key, String lang, String value) {
        Translation translation = new Translation();
        translation.setId(id);
        translation.setType(type);
        translation.setTkey(key);
        translation.setLang(lang);
        translation.setTvalue(value);
        translation.setProp(0);
        translation.setIsDel(0);
        return translation;
    }
}
