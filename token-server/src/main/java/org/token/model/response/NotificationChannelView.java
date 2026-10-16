package org.token.model.response;

import java.time.LocalDateTime;
import java.util.Map;

public record NotificationChannelView(
        Long id,
        String name,
        String channelType,
        Integer enabled,
        Map<String, Object> config,
        String defaultRecipients,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
