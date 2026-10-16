package org.token.config;

import org.token.repository.dao.LanguageMapper;
import org.token.tools.cache.HybridCacheService;
import org.token.model.entity.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.time.Duration;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class EnabledLocaleResolverTest {

    @Mock
    private LanguageMapper languageMapper;

    private EnabledLocaleResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new EnabledLocaleResolver(new org.token.repository.system.LanguageRepository(languageMapper));
        ReflectionTestUtils.setField(resolver, "defaultLanguage", "zh");
    }

    @Test
    void resolvesExactEnabledRegionalLanguage() {
        enable("zh", "en", "pt-br");

        assertEquals("pt-br", resolver.resolve("pt-BR"));
    }

    @Test
    void resolvesRegionalRequestToEnabledBaseLanguage() {
        enable("zh", "en");

        assertEquals("en", resolver.resolve("en-US"));
    }

    @Test
    void honorsAcceptLanguageQualityWeights() {
        enable("zh", "en", "fr");

        assertEquals("fr", resolver.resolve("en;q=0.4, fr-FR;q=0.9, zh;q=0.2"));
    }

    @Test
    void fallsBackToEnglishWhenRequestedLanguageIsNotEnabled() {
        enable("zh", "en");

        assertEquals("en", resolver.resolve("de-DE"));
    }

    @Test
    void returnsEnglishAsSafeFallbackWhenNoLanguageIsConfigured() {
        when(languageMapper.findOrdered(true)).thenReturn(List.of());

        assertEquals("en", resolver.resolve("en-US"));
        assertEquals(List.of("en-us", "en"), resolver.lookupCandidates("en-US"));
        assertEquals(List.of("es-mx", "es", "en"), resolver.lookupCandidates("es-MX"));
    }

    @Test
    void removesRealTabsAndLineBreaksFromSharedCacheLabels() {
        HybridCacheService sharedCache = mock(HybridCacheService.class);
        resolver.setSharedCache(sharedCache);
        Language language = language("zh");
        language.setNativeName("简\t体\r\n中文");
        when(languageMapper.findOrdered(true)).thenReturn(List.of(language));

        resolver.enabledLanguages();

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(sharedCache).put(eq("i18n:enabled-languages"), value.capture(), eq(Duration.ofMinutes(5)));
        assertEquals("简 体  中文\tzh", value.getValue());
    }

    private void enable(String... languageCodes) {
        List<Language> languages = java.util.Arrays.stream(languageCodes)
                .map(this::language)
                .toList();
        when(languageMapper.findOrdered(true)).thenReturn(languages);
    }

    private Language language(String languageCode) {
        Language language = new Language();
        language.setLang(languageCode);
        language.setName(languageCode);
        language.setNativeName(languageCode);
        language.setStatus(1);
        return language;
    }
}
