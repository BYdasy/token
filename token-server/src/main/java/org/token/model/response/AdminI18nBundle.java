package org.token.model.response;

import java.util.List;
import java.util.Map;

/**
 * 管理端启动时使用的多语言资源包。
 */
public record AdminI18nBundle(
        String locale,
        List<I18nLanguageOption> languages,
        Map<String, String> messages
) {
}
