package org.token.model.request.notification;

import jakarta.validation.constraints.Positive;

public record TelegramBindingListRequest(@Positive(message = "server.notification.channel.notFound") Long channelId) { }
