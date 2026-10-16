package org.token.model.request.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 发送入口只接收通知对象、标题和 HTML 正文，其余策略由入口决定。 */
public record NotificationSendRequest(
        @NotNull(message = "server.notification.recipient.required")
        @Valid NotificationTargetRequest target,
        @Size(max = 300, message = "server.notification.field.tooLong") String title,
        @Size(max = 65535, message = "server.notification.field.tooLong") String content,
        Boolean popup,
        @Size(max=16,message="server.notification.field.tooLong") String severity,
        @Size(max=100,message="server.notification.field.tooLong") String templateCode,
        java.util.Map<String,String> variables
) {
    public NotificationSendRequest(NotificationTargetRequest target,String title,String content,Boolean popup){this(target,title,content,popup,null,null,null);}

    /** 未传 popup 时保持兼容：只发送站内消息。 */
    public boolean popupEnabled() {
        return Boolean.TRUE.equals(popup);
    }
}
