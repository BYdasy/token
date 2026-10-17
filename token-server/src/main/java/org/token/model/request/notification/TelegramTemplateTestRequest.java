package org.token.model.request.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 测试始终只发送给一个已保存绑定，不接受 ALL 或任意 chatId。 */
public record TelegramTemplateTestRequest(
        @NotNull(message = "server.notification.template.notFound") @Valid TelegramTemplatePreviewRequest template,
        @NotNull(message = "server.notification.channel.required")
        @Positive(message = "server.notification.channel.notFound") Long channelId,
        @NotNull(message = "server.notification.telegram.targetsInvalid")
        @Positive(message = "server.notification.telegram.targetsInvalid") Long bindingId
) { }
