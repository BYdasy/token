package org.token.service.impl;

import org.token.config.EnabledLocaleResolver;
import org.token.repository.dao.LanguageMapper;
import org.token.model.entity.Language;
import org.token.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LanguageEnglishRequiredTest {
    @Test
    void adminOptionsDisplayChineseAndKeepNativeNamesForSearch() {
        var service = spy(new LanguageServiceImpl(mock(EnabledLocaleResolver.class)));
        Language french = new Language(); french.setLang("fr"); french.setName("French");
        french.setChineseName("法语"); french.setNativeName("Français");
        Language german = new Language(); german.setLang("de"); german.setName("German"); german.setNativeName("Deutsch");
        Language arabic = new Language(); arabic.setLang("ar"); arabic.setName("阿拉伯语"); arabic.setChineseName("Arabic"); arabic.setNativeName("العربية");
        doReturn(java.util.List.of(french, german, arabic)).when(service).listLanguages(true);
        var options = (java.util.List<java.util.Map<String, Object>>) service.overview(true).get("options");
        assertEquals("法语", options.get(0).get("label"));
        assertEquals("Français", options.get(0).get("nativeName"));
        assertEquals("德语", options.get(1).get("label"));
        assertEquals("阿拉伯语", options.get(2).get("label"));
        assertEquals("العربية", options.get(2).get("nativeName"));
    }

    @Test void editingStatusAndSortDoesNotRewriteLanguageNames() {
        LanguageMapper mapper = mock(LanguageMapper.class);
        Language stored = new Language(); stored.setId(3L); stored.setLang("es"); stored.setName("Spanish");
        stored.setChineseName("西班牙语"); stored.setNativeName("Español"); stored.setStatus(1); stored.setSortNo(3);
        when(mapper.selectById(3L)).thenReturn(stored);
        when(mapper.updateById(any(Language.class))).thenReturn(1);
        var service = new LanguageServiceImpl(mock(EnabledLocaleResolver.class));
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.LanguageRepository(mapper));
        Language request = new Language(); request.setId(3L); request.setStatus(0); request.setSortNo(8);
        request.setLang("ar"); request.setName("旧客户端误填"); request.setChineseName("Wrong"); request.setNativeName("Wrong");
        Language result = service.updateLanguage(request);
        assertEquals("es", result.getLang()); assertEquals("Spanish", result.getName());
        assertEquals("西班牙语", result.getChineseName()); assertEquals("Español", result.getNativeName());
        assertEquals(0, result.getStatus()); assertEquals(8, result.getSortNo());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, -1})
    void rejectsAnyNonEnabledEnglishStatus(int status) {
        LanguageMapper mapper = mock(LanguageMapper.class);
        Language stored = new Language(); stored.setId(2L); stored.setLang("en"); stored.setStatus(1);
        when(mapper.selectById(2L)).thenReturn(stored);
        var service = new LanguageServiceImpl(mock(EnabledLocaleResolver.class));
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.LanguageRepository(mapper));
        Language request = new Language(); request.setId(2L); request.setStatus(status);
        assertThrows(BusinessException.class, () -> service.updateLanguage(request));
        verify(mapper, never()).updateById(any(Language.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {1})
    void allowsEnabledStatusAndUnchangedStatus(Integer status) {
        LanguageMapper mapper = mock(LanguageMapper.class);
        Language stored = new Language(); stored.setId(2L); stored.setLang("en"); stored.setStatus(1);
        when(mapper.selectById(2L)).thenReturn(stored);
        when(mapper.updateById(any(Language.class))).thenReturn(1);
        var service = new LanguageServiceImpl(mock(EnabledLocaleResolver.class));
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.LanguageRepository(mapper));
        Language request = new Language(); request.setId(2L); request.setStatus(status);
        assertEquals(1, service.updateLanguage(request).getStatus());
    }
}
