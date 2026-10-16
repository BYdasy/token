package org.token.model.request.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record ChannelConfigSaveRequest(
        Long id,
        @NotBlank(message = "server.notification.channel.nameRequired")
        @Size(max = 100, message = "server.notification.field.tooLong") String name,
        @NotBlank(message = "server.notification.channel.typeInvalid")
        @Size(max = 20, message = "server.notification.field.tooLong") String channelType,
        Integer enabled,
        Map<String, Object> config,
        @Size(max = 4000, message = "server.notification.field.tooLong") String defaultRecipients
) {
}
