package org.token.model.request.notification;

import jakarta.validation.constraints.NotNull;

public record NotificationReadRequest(@NotNull(message = "server.notification.id.required") Long id) {
}
