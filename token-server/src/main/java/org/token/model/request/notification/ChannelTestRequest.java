package org.token.model.request.notification;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChannelTestRequest(
        @NotNull(message = "server.notification.id.required") Long id,
        @Size(max = 4000, message = "server.notification.field.tooLong") String recipients
) {
}
