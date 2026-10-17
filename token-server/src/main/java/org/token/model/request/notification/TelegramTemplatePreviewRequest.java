package org.token.model.request.notification;

import jakarta.validation.constraints.*;
import java.util.Map;

/** 预览数据库中选定的模板；只接收样例变量，不接受替换正文。 */
public record TelegramTemplatePreviewRequest(
        @NotNull(message = "server.notification.template.notFound")
        @Positive(message = "server.notification.template.notFound") Long templateId,
        @Size(max = 100, message = "server.notification.field.tooLong")
        Map<@Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,99}", message = "server.notification.template.testVariablesInvalid") String,
                @NotNull(message = "server.notification.template.testVariablesInvalid")
                @Size(max = 2000, message = "server.notification.field.tooLong") String> variables,
        @Pattern(regexp = "[a-zA-Z]{2,3}(?:[-_][a-zA-Z0-9]{2,8})*", message = "server.notification.template.testLanguageInvalid")
        @Size(max = 20, message = "server.notification.field.tooLong") String language
) { }
