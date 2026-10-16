package org.token.model.request.notification;

import jakarta.validation.constraints.NotNull;

public record TelegramUpdatesRequest(
        @NotNull(message = "server.notification.id.required") Long configId
) {
}
