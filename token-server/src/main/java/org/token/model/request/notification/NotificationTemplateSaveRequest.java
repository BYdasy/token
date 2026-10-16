package org.token.model.request.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.util.List;

public record NotificationTemplateSaveRequest(
        Long id,
        @Size(max = 100, message = "server.notification.field.tooLong") String code,
        @NotBlank(message = "server.notification.template.nameRequired")
        @Size(max = 100, message = "server.notification.field.tooLong") String name,
        @Size(max = 20, message = "server.notification.field.tooLong") String channelType,
        @Size(max = 300, message = "server.notification.field.tooLong") String titleTemplate,
        @NotBlank(message = "server.notification.template.contentRequired")
        @Size(max = 65535, message = "server.notification.field.tooLong") String contentTemplate,
        Integer enabled,
        @Valid List<NotificationTemplateLocalizationRequest> translations
) {
}
