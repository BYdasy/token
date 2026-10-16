package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.model.security.JwtUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;

/** 站内通知、实时弹窗与后台发送接口。 */
@RestController
@RequestMapping("/api/system/notifications")
@RequiredArgsConstructor
public class NotificationStreamController {

    private final org.token.service.NotificationPushService notificationPushService;

    @GetMapping(value = "/my/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return notificationPushService.subscribe(principal.getUid());
    }

}
