package org.token.tools.translation;

import com.google.cloud.translate.Translate;
import com.google.cloud.translate.Translation;
import org.token.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranslationUtilsHtmlTest {
    private TranslationUtils translations;
    private Translate provider;

    @BeforeEach void setup() {
        translations = new TranslationUtils(); provider = mock(Translate.class);
        ReflectionTestUtils.setField(translations, "translate", provider);
        ReflectionTestUtils.setField(translations, "translationExecutor", Executors.newSingleThreadExecutor());
        ReflectionTestUtils.setField(translations, "translateTimeoutSeconds", 5);
    }
    @AfterEach void close() { translations.destroy(); }

    @Test void sharedHtmlPathUsesBatchTextAndRestoresBusinessPlaceholders() throws Exception {
        when(provider.translate(anyList(), any(Translate.TranslateOption[].class))).thenAnswer(call -> {
            List<String> source=call.getArgument(0);
            assertEquals(List.of("标题", "内容 __CMD_PLACEHOLDER_0__"), source);
            return source.stream().map(value -> result(value.replace("标题", "Heading").replace("内容", "Body"))).toList();
        });
        assertEquals("<p><strong>Heading</strong><br><br>Body {amount}</p>", translations.translateToMultipleLanguagesParallel(
                "<p><strong>标题</strong><br><br>内容 {amount}</p>", List.of("en"), "html").get("en"));
        verify(provider, never()).translate(anyString(), any(Translate.TranslateOption[].class));
    }

    @Test void corruptPlaceholderDoesNotPersistAsASuccessfulTranslation() {
        Translation corrupt = result("Amount");
        when(provider.translate(anyList(), any(Translate.TranslateOption[].class))).thenReturn(List.of(corrupt));
        assertThrows(BusinessException.class, () -> translations.translateToMultipleLanguagesParallel("<p>{amount}</p>", List.of("en"), "html"));
    }

    @Test void telegramStillUsesItsDedicatedHtmlProtocol() throws Exception {
        when(provider.translate(anyString(), any(Translate.TranslateOption[].class))).thenAnswer(call -> result(call.getArgument(0)));
        assertEquals("Copy <code>EXP-001</code>", translations.translateToMultipleLanguagesParallel("Copy `EXP-001`", List.of("en"), "telegram").get("en"));
        verify(provider, never()).translate(anyList(), any(Translate.TranslateOption[].class));
    }

    @Test void regionalLanguageResponsesKeepExactKeysForDraftFilling() throws Exception {
        when(provider.translate(anyString(), any(Translate.TranslateOption[].class))).thenAnswer(call -> result("Translated"));
        var result=translations.translateToMultipleLanguagesParallel("最新中文", List.of("EN_us", "en", "pt_BR", "pt", "zh-TW", "mni-Mtei"), "text");
        assertEquals(List.of("en-us", "en", "pt-br", "pt", "zh-tw", "mni-mtei"), List.copyOf(result.keySet()));
        assertTrue(result.values().stream().allMatch("Translated"::equals));
        verify(provider, times(6)).translate(anyString(), any(Translate.TranslateOption[].class));
    }

    @Test void regionalRichTextKeepsMarkupAndUsesTheRequestedKey() throws Exception {
        when(provider.translate(anyList(), any(Translate.TranslateOption[].class))).thenAnswer(call -> {
            List<String> parts=call.getArgument(0);
            return parts.stream().map(part->result("Translated")).toList();
        });
        var result=translations.translateToMultipleLanguagesParallel("<p>段落<br><br>正文</p>", List.of("pt-BR"), "html");
        assertEquals(java.util.Map.of("pt-br", "<p>Translated<br><br>Translated</p>"),result);
    }

    @Test void providerAliasesDoNotEraseChineseScriptOrOtherSupportedVariants() {
        assertEquals("zh-TW", TranslationUtils.providerLanguage("zh-tw"));
        assertEquals("zh-TW", TranslationUtils.providerLanguage("zh-hant-hk"));
        assertEquals("zh-CN", TranslationUtils.providerLanguage("zh-hans"));
        assertEquals("pt-BR", TranslationUtils.providerLanguage("pt-br"));
        assertEquals("mni-Mtei", TranslationUtils.providerLanguage("mni-mtei"));
        assertEquals("en", TranslationUtils.providerLanguage("en-us"));
    }

    private static Translation result(String text) { Translation value=mock(Translation.class); when(value.getTranslatedText()).thenReturn(text); return value; }
}
