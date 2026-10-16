package org.token.util;

import java.util.Locale;

/** 后台中文语言名称；兼容历史 name/chinese_name 内容错位，不修改原始配置。 */
public final class LanguageDisplayNames {
    private LanguageDisplayNames() {}

    public static String chinese(Object code, Object... configuredNames) {
        for (Object configuredName : configuredNames) {
            String configured = text(configuredName);
            if (configured.codePoints().anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN))
                return configured;
        }
        String language = text(code).replace('_', '-');
        if (language.isEmpty()) return "";
        String display = Locale.forLanguageTag(language).getDisplayName(Locale.SIMPLIFIED_CHINESE);
        return display.isBlank() ? language : display;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
