package org.token.model.request.notification;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

/** 手动发送仅接受数据库绑定编号，不接受客户端指定任意聊天目标。 */
public record TelegramTemplateSendRequest(
        @NotNull(message = "server.notification.channel.required")
        @Positive(message = "server.notification.channel.notFound") Long channelId,
        @NotBlank(message = "server.notification.telegram.targetsInvalid")
        @Pattern(regexp = "ALL|SELECTED", message = "server.notification.telegram.targetsInvalid") String targetMode,
        List<@NotNull @Positive Long> bindingIds,
        @Size(max = 100, message = "server.notification.field.tooLong") String templateCode,
        @Size(max = 300, message = "server.notification.field.tooLong") String title,
        @Size(max = 65535, message = "server.notification.field.tooLong") String content,
        @Size(max = 100, message = "server.notification.field.tooLong") Map<String, String> variables,
        @Size(max = 20, message = "server.notification.field.tooLong") String language
) { }
