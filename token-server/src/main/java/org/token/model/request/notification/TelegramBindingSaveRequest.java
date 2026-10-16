package org.token.model.request.notification;

import jakarta.validation.constraints.*;

public record TelegramBindingSaveRequest(
        @Positive(message = "server.notification.telegram.bindingInvalid") Long id,
        @NotNull(message = "server.notification.channel.required")
        @Positive(message = "server.notification.channel.notFound") Long channelId,
        @NotBlank(message = "server.notification.telegram.bindingInvalid")
        @Pattern(regexp = "-?[1-9][0-9]{0,19}", message = "server.notification.telegram.bindingInvalid") String chatId,
        @NotBlank(message = "server.notification.telegram.bindingInvalid")
        @Size(max = 100, message = "server.notification.field.tooLong") String name,
        @NotBlank(message = "server.notification.telegram.bindingInvalid")
        @Pattern(regexp = "PRIVATE|GROUP|CHANNEL", message = "server.notification.telegram.bindingInvalid") String chatType,
        @Min(value = 0, message = "server.notification.telegram.bindingInvalid")
        @Max(value = 1, message = "server.notification.telegram.bindingInvalid") Integer enabled
) { }
