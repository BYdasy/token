package org.token.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.request.notification.NotificationReadRequest;
import org.token.model.request.notification.NotificationSendRequest;
import org.token.model.request.QueryParams;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.NotificationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 站内通知、实时弹窗与后台发送接口。 */
@RestController
@RequestMapping("/api/system/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private static final String READ_PERMISSION = "hasAnyAuthority('60701','super')";
    private static final String SEND_PERMISSION = "hasAnyAuthority('60702','super')";

    private final NotificationService notificationService;

    private final ResultFactory resultFactory;

    @PostMapping("/my/list")
    public Result myList(@AuthenticationPrincipal JwtUserPrincipal principal,
                         @RequestBody(required = false) QueryParams params,
                         @RequestParam(defaultValue = "false") boolean includeHidden) {
        return resultFactory.success(notificationService.myNotifications(principal.getUid(), params, includeHidden));
    }

    @GetMapping("/my/unread-count")
    public Result unreadCount(@AuthenticationPrincipal JwtUserPrincipal principal) {
        return resultFactory.success(Map.of("count", notificationService.unreadCount(principal.getUid())));
    }

    @PostMapping("/my/read")
    @LogAction(type = "后台通知已读")
    public Result read(@AuthenticationPrincipal JwtUserPrincipal principal,
                       @Valid @RequestBody NotificationReadRequest request) {
        notificationService.markRead(principal.getUid(), request.id());
        return resultFactory.success();
    }

    @PostMapping("/my/read-all")
    @LogAction(type = "后台通知全部已读")
    public Result readAll(@AuthenticationPrincipal JwtUserPrincipal principal) {
        notificationService.markAllRead(principal.getUid());
        return resultFactory.success();
    }

    @PostMapping("/my/hide")
    @LogAction(type = "后台通知隐藏")
    public Result hide(@AuthenticationPrincipal JwtUserPrincipal principal,
                       @Valid @RequestBody NotificationReadRequest request) {
        notificationService.hideForUser(principal.getUid(), request.id());
        return resultFactory.success();
    }

    @PostMapping("/my/clear")
    @LogAction(type = "后台通知清空")
    public Result clear(@AuthenticationPrincipal JwtUserPrincipal principal) {
        notificationService.clearForUser(principal.getUid());
        return resultFactory.success();
    }

    @PostMapping("/manage/list")
    @PreAuthorize(READ_PERMISSION)
    public Result manageList(@RequestBody(required = false) QueryParams params) {
        return resultFactory.success(notificationService.manageNotifications(params));
    }

    @PostMapping("/targets")
    @PreAuthorize(SEND_PERMISSION)
    public Result targets() {
        return resultFactory.success(notificationService.targets());
    }

    @PostMapping("/send")
    @PreAuthorize(SEND_PERMISSION)
    @LogAction(type = "发送通知")
    public Result send(@AuthenticationPrincipal JwtUserPrincipal principal,
                       @Valid @RequestBody NotificationSendRequest request) {
        int count = notificationService.send(request, principal.getUid());
        return resultFactory.success(Map.of("recipientCount", count));
    }

}
