package org.token.model.request.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 通知对象；type 支持 ALL、USER、ROLE。 */
public record NotificationTargetRequest(
        @NotBlank(message = "server.notification.targetType.invalid")
        @Size(max = 16, message = "server.notification.field.tooLong") String type,
        List<Integer> ids
) {
}
