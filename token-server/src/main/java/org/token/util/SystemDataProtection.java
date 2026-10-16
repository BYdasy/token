package org.token.util;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 系统内置数据保护规则。
 *
 * <p>优先使用稳定的业务字段判断；旧表没有“是否内置”字段的翻译模板，
 * 继续使用历史约定的固定 ID，并在这里集中维护，避免规则散落在控制器中。</p>
 */
public final class SystemDataProtection {

    /** 旧版初始化脚本固定的默认管理员 UID。 */
    public static final int LEGACY_DEFAULT_ADMIN_UID = 1;

    /** 默认管理员的稳定登录名。 */
    public static final String DEFAULT_ADMIN_USERNAME = "admin";

    /** 服务端运行时依赖的中文基础语言。 */
    public static final String BASE_LANGUAGE_CODE = "zh";

    /**
     * 旧版 translates 表没有系统标记，内置模板历史 ID 固定为 1 到 7。
     * 使用显式集合，避免把以后恰好落在某个区间的自定义数据误判为内置数据。
     */
    public static final Set<Integer> SYSTEM_TRANSLATION_TEMPLATE_IDS =
            Set.of(1, 2, 3, 4, 5, 6, 7);

    private SystemDataProtection() {
    }

    public static boolean isDefaultAdmin(Integer uid, String username) {
        return Integer.valueOf(LEGACY_DEFAULT_ADMIN_UID).equals(uid)
                || DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(normalize(username));
    }

    public static boolean isBaseLanguage(String languageCode) {
        return BASE_LANGUAGE_CODE.equals(normalizeLanguage(languageCode));
    }

    public static boolean isSystemTranslationTemplate(Integer templateId) {
        return templateId != null && SYSTEM_TRANSLATION_TEMPLATE_IDS.contains(templateId);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private static String normalizeLanguage(String value) {
        String normalized = normalize(value).replace('_', '-').toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('-');
        return separator > 0 ? normalized.substring(0, separator) : normalized;
    }
}
