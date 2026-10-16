package org.token.model.response;

/** 通知模板页面可编辑的后台启用语言。 */
public record NotificationTemplateLanguageView(
        String lang,
        String name,
        String nativeName
) {
}
