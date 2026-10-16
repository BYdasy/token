package org.token.service.impl;

import lombok.RequiredArgsConstructor;
import org.token.model.request.notification.NotificationTemplateTranslateRequest;
import org.token.model.response.NotificationTemplateLocalizationView;
import org.token.model.response.NotificationTemplateTranslationResult;
import org.token.exception.BusinessException;
import org.token.service.LanguageService;
import org.token.service.NotificationTemplateTranslationService;
import org.token.tools.translation.TranslationUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把通知模板中的中文标题和正文翻译到后台已启用语言。
 *
 * 这里只返回表单可直接合并的结果，最终仍由通知模板保存接口统一落库，
 * 因此页面无需跳转到翻译管理，也不会产生半份模板数据。
 */
@Service
@RequiredArgsConstructor
public class NotificationTemplateTranslationServiceImpl
        implements NotificationTemplateTranslationService {

    private static final Set<String> CHANNEL_TYPES = Set.of(
            "EMAIL", "TELEGRAM", "DINGTALK", "INTERNAL"
    );

    private final LanguageService languageService;
    private final TranslationUtils translationUtils;

    @Override
    public NotificationTemplateTranslationResult translate(NotificationTemplateTranslateRequest request)
            throws InterruptedException {
        if (request == null) {
            throw new BusinessException("server.notification.template.required");
        }
        String channelType = normalizeChannelType(request.channelType());
        String sourceLanguage = normalizeLanguage(request.sourceLang(), "zh");
        List<String> targets = resolveTargetLanguages(request.targetLanguages(), sourceLanguage);
        if (targets.isEmpty()) {
            return new NotificationTemplateTranslationResult(List.of());
        }

        Map<String, String> translatedTitles = StringUtils.hasText(request.title())
                ? translationUtils.translateToMultipleLanguagesParallel(
                        request.title(), targets, "text")
                : Map.of();
        Map<String, String> translatedContents = translationUtils.translateToMultipleLanguagesParallel(
                request.content(), targets, translationFormat(channelType));

        List<NotificationTemplateLocalizationView> translations = new ArrayList<>(targets.size());
        for (String target : targets) {
            String translatedTitle = translatedValue(translatedTitles, target);
            String translatedContent = translatedValue(translatedContents, target);
            translations.add(new NotificationTemplateLocalizationView(
                    target,
                    StringUtils.hasText(request.title()) ? translatedTitle : null,
                    translatedContent
            ));
        }
        return new NotificationTemplateTranslationResult(List.copyOf(translations));
    }

    private List<String> resolveTargetLanguages(List<String> requested, String sourceLanguage) {
        List<String> enabledLanguages = languageService.enabledLanguageCodes();
        if (enabledLanguages == null) {
            enabledLanguages = List.of();
        }
        Set<String> enabled = new LinkedHashSet<>();
        for (String language : enabledLanguages) {
            String normalized = normalizeLanguage(language, null);
            if (StringUtils.hasText(normalized)) {
                enabled.add(normalized);
            }
        }

        List<String> candidates = requested == null || requested.isEmpty()
                ? new ArrayList<>(enabled)
                : requested;
        Set<String> targets = new LinkedHashSet<>();
        for (String language : candidates) {
            String normalized = normalizeLanguage(language, null);
            if (!StringUtils.hasText(normalized) || normalized.equals(sourceLanguage)) {
                continue;
            }
            if (!enabled.contains(normalized)) {
                throw new BusinessException("server.notification.template.languageNotEnabled");
            }
            targets.add(normalized);
        }
        return List.copyOf(targets);
    }

    private String normalizeChannelType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CHANNEL_TYPES.contains(normalized)) {
            throw new BusinessException("server.notification.channel.typeInvalid");
        }
        return normalized;
    }

    private String normalizeLanguage(String value, String defaultValue) {
        String normalized = StringUtils.hasText(value) ? value.trim() : defaultValue;
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        normalized = normalized.replace('_', '-').toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$")) {
            throw new BusinessException("server.language.lang.invalid");
        }
        return normalized;
    }

    private String translationFormat(String channelType) {
        return switch (channelType) {
            case "EMAIL", "INTERNAL" -> "html";
            case "TELEGRAM" -> "telegram";
            default -> "text";
        };
    }

    /** TranslationUtils 会把地区码收敛为基础语言，结果读取时兼容两种键。 */
    private String translatedValue(Map<String, String> translations, String language) {
        String exact = translations.get(language);
        if (exact != null) {
            return exact;
        }
        int separator = language.indexOf('-');
        String baseLanguage = separator > 0 ? language.substring(0, separator) : language;
        return translations.getOrDefault(baseLanguage, "");
    }
}
