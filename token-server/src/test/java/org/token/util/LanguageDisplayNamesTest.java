package org.token.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LanguageDisplayNamesTest {
    @Test void legacyEnglishChineseColumnUsesActualChineseName() {
        assertEquals("阿拉伯语", LanguageDisplayNames.chinese("ar", "Arabic", "阿拉伯语"));
        assertEquals("英语", LanguageDisplayNames.chinese("en", "English", "英语"));
        assertEquals("自定义中文", LanguageDisplayNames.chinese("ar", "自定义中文", "阿拉伯语"));
    }

    @Test void localeFallbackPreservesRegionAndScript() {
        assertEquals("阿拉伯语", LanguageDisplayNames.chinese("ar", "Arabic"));
        assertTrue(LanguageDisplayNames.chinese("ar_SA", "Arabic").contains("沙特阿拉伯"));
        assertTrue(LanguageDisplayNames.chinese("pt_BR", "Portuguese").contains("巴西"));
        assertTrue(LanguageDisplayNames.chinese("zh_Hant", "Chinese").contains("繁体"));
        assertEquals("", LanguageDisplayNames.chinese(null, (Object) null));
    }
}
