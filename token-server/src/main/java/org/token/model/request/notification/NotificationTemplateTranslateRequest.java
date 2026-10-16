package org.token.model.request.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 通知模板页面的一键多语言翻译请求。 */
public record NotificationTemplateTranslateRequest(
        @Size(max = 20, message = "server.notification.field.tooLong") String channelType,
        @Size(max = 20, message = "server.notification.field.tooLong") String sourceLang,
        @Size(max = 300, message = "server.notification.field.tooLong") String title,
        @NotBlank(message = "server.notification.template.contentRequired")
        @Size(max = 65535, message = "server.notification.field.tooLong") String content,
        List<@Size(max = 20, message = "server.notification.field.tooLong") String> targetLanguages
) {
}
