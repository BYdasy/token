package org.token.model.response;

import java.time.LocalDateTime;

/** SSE 推送负载；content 为可信后台生成或有发送权限账号提交的 HTML。 */
public record NotificationPushEvent(
        String eventId,
        Long notificationId,
        String title,
        String content,
        String displayType,
        Integer readStatus,
        LocalDateTime createdAt,
        String severity
) {
    public NotificationPushEvent(String eventId,Long notificationId,String title,String content,String displayType,Integer readStatus,LocalDateTime createdAt){
        this(eventId,notificationId,title,content,displayType,readStatus,createdAt,"INFO");
    }
}
