package org.token.tools.response;

import org.token.model.response.Result;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.token.config.EnabledLocaleResolver;
import org.token.service.TranslationService;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 创建所有接口统一使用的 JSON 响应结构。
 */
@Component
@RequiredArgsConstructor
public class ResultFactory {

    private final TranslationService translationService;

    private final EnabledLocaleResolver localeResolver;

    public Result success() {
        return success(null);
    }

    public Result success(Object data) {
        return new Result(0, data, translate("server.success"));
    }

    public Result failure(Integer code, String messageCode) {
        return new Result(code, null, translate(messageCode));
    }

    public Result failure(Integer code, String messageCode, HttpServletRequest request) {
        return new Result(code, null, translate(messageCode, localeResolver.resolve(request)));
    }

    public Result create(Integer code, Object data, String messageCode) {
        return new Result(code, data, translate(messageCode));
    }

    public Result create(Integer code, Object data, String messageCode, Map<String, String> replacements) {
        return new Result(code, data, replaceMessage(translate(messageCode), replacements));
    }

    private String translate(String messageCode) {
        if (messageCode == null || messageCode.isBlank()) {
            return "";
        }

        return translate(messageCode, LocaleContextHolder.getLocale().toLanguageTag());
    }

    private String translate(String messageCode, String language) {
        try {
            String message = translationService.getTranslate(messageCode, language);
            return message == null || message.isBlank() ? messageCode : message;
        } catch (Exception ignored) {
            return messageCode;
        }
    }

    private String replaceMessage(String message, Map<String, String> replacements) {
        if (message == null || message.isBlank() || replacements == null || replacements.isEmpty()) {
            return message;
        }

        String result = message;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
