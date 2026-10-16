package org.token.model.request.notification;

import jakarta.validation.constraints.NotNull;

public record NotificationIdRequest(@NotNull(message = "server.notification.id.required") Long id) {
}
