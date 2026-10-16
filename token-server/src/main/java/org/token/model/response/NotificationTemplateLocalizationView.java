package org.token.model.response;

/** 一个通知模板在指定语言下的标题与 HTML 正文。 */
public record NotificationTemplateLocalizationView(
        String lang,
        String title,
        String content
) {
}
