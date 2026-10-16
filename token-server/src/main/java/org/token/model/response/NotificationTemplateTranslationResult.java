package org.token.model.response;

import java.util.List;

/** 通知模板页面的一键翻译结果。 */
public record NotificationTemplateTranslationResult(
        List<NotificationTemplateLocalizationView> translations
) {
}
