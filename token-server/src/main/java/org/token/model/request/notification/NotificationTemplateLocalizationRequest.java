package org.token.model.request.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 单个启用语言的通知模板标题与 HTML 正文。 */
public record NotificationTemplateLocalizationRequest(
        @NotBlank(message = "server.notification.template.languageRequired")
        @Size(max = 20, message = "server.notification.field.tooLong") String lang,
        @Size(max = 300, message = "server.notification.field.tooLong") String title,
        @Size(max = 65535, message = "server.notification.field.tooLong") String content
) {
}
