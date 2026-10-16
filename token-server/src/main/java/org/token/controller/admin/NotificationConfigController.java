package org.token.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.request.notification.ChannelConfigSaveRequest;
import org.token.model.request.notification.ChannelTestRequest;
import org.token.model.request.notification.NotificationIdRequest;
import org.token.model.request.notification.NotificationTemplateSaveRequest;
import org.token.model.request.notification.NotificationTemplateTranslateRequest;
import org.token.model.request.notification.TelegramUpdatesRequest;
import org.token.model.request.notification.TelegramBindingListRequest;
import org.token.model.request.notification.TelegramBindingSaveRequest;
import org.token.model.request.notification.TelegramTemplateSendRequest;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.NotificationConfigService;
import org.token.service.NotificationTemplateTranslationService;
import org.token.service.impl.TelegramNotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 邮件、Telegram、钉钉渠道及通知模板管理。 */
@RestController
@RequestMapping("/api/system/notification-config")
@RequiredArgsConstructor
public class NotificationConfigController {

    private static final String CHANNEL_READ_PERMISSION =
            "hasAnyAuthority('607031','super')";
    private static final String CHANNEL_WRITE_PERMISSION =
            "hasAnyAuthority('607032','super')";
    private static final String CHANNEL_SEND_PERMISSION =
            "hasAnyAuthority('60702','607032','super')";
    private static final String CHANNEL_DELETE_PERMISSION =
            "hasAnyAuthority('607033','super')";
    private static final String NOTIFICATION_SEND_PERMISSION =
            "hasAnyAuthority('60702','super')";
    private static final String TEMPLATE_READ_PERMISSION =
            "hasAnyAuthority('607041','super')";
    private static final String TEMPLATE_WRITE_PERMISSION =
            "hasAnyAuthority('607042','super')";
    private static final String TEMPLATE_DELETE_PERMISSION =
            "hasAnyAuthority('607043','super')";

    private final NotificationConfigService configService;
    private final NotificationTemplateTranslationService templateTranslationService;
    private final TelegramNotificationService telegramNotifications;
    private final ResultFactory resultFactory;

    @PostMapping("/channel/list")
    @PreAuthorize(CHANNEL_READ_PERMISSION)
    public Result channelList() {
        return resultFactory.success(configService.channels());
    }

    @PostMapping("/channel/save")
    @PreAuthorize(CHANNEL_WRITE_PERMISSION)
    @LogAction(type = "通知渠道保存")
    public Result channelSave(@Valid @RequestBody ChannelConfigSaveRequest request) {
        return resultFactory.success(configService.saveChannel(request));
    }

    @PostMapping("/channel/delete")
    @PreAuthorize(CHANNEL_DELETE_PERMISSION)
    @LogAction(type = "通知渠道删除")
    public Result channelDelete(@Valid @RequestBody NotificationIdRequest request) {
        configService.deleteChannel(request.id());
        return resultFactory.success();
    }

    @PostMapping("/channel/test")
    @LogAction(type = "通知渠道测试")
    @PreAuthorize(CHANNEL_SEND_PERMISSION)
    public Result channelTest(@Valid @RequestBody ChannelTestRequest request,
                              @AuthenticationPrincipal JwtUserPrincipal principal) {
        configService.testChannel(request.id(), request.recipients(), principal == null ? null : principal.getLocale());
        return resultFactory.success();
    }

    @PostMapping("/telegram/updates")
    @LogAction(type = "Telegram会话获取")
    @PreAuthorize(CHANNEL_WRITE_PERMISSION)
    public Result telegramUpdates(@Valid @RequestBody TelegramUpdatesRequest request,
                                  @AuthenticationPrincipal JwtUserPrincipal principal) {
        return resultFactory.success(configService.telegramUpdates(
                request.configId(), principal == null ? null : principal.getLocale()));
    }

    @PostMapping("/telegram/inspect")
    @LogAction(type = "Telegram连接检查")
    @PreAuthorize(CHANNEL_WRITE_PERMISSION)
    public Result inspectTelegram(@Valid @RequestBody NotificationIdRequest request) {
        return resultFactory.success(configService.inspectTelegram(request.id()));
    }

    @PostMapping("/telegram/bindings")
    @PreAuthorize(CHANNEL_READ_PERMISSION)
    public Result telegramBindings(@Valid @RequestBody(required = false) TelegramBindingListRequest request) {
        return resultFactory.success(telegramNotifications.list(request == null ? null : request.channelId()));
    }

    @PostMapping("/telegram/binding/save")
    @PreAuthorize(CHANNEL_WRITE_PERMISSION)
    @LogAction(type = "Telegram接收绑定保存")
    public Result saveTelegramBinding(@Valid @RequestBody TelegramBindingSaveRequest request) {
        return resultFactory.success(telegramNotifications.save(request));
    }

    @PostMapping("/telegram/send-options")
    @PreAuthorize("hasAnyAuthority('60702','60101','60102','super')")
    public Result telegramSendOptions() {
        return resultFactory.success(telegramNotifications.sendOptions());
    }

    @PostMapping("/template/send")
    @PreAuthorize(NOTIFICATION_SEND_PERMISSION)
    @LogAction(type = "发送Telegram通知")
    public Result sendTelegramTemplate(@Valid @RequestBody TelegramTemplateSendRequest request,
                                      @AuthenticationPrincipal JwtUserPrincipal principal) {
        int count = telegramNotifications.send(request, principal == null ? null : principal.getLocale());
        return resultFactory.success(java.util.Map.of("recipientCount", count));
    }

    @PostMapping("/email/google/authorize")
    @LogAction(type = "邮箱授权发起")
    @PreAuthorize(CHANNEL_WRITE_PERMISSION)
    public Result googleAuthorize(@RequestBody java.util.Map<String, Object> request) {
        Long configId = request == null || request.get("configId") == null
                ? null : Long.valueOf(String.valueOf(request.get("configId")));
        String redirectUri = request == null || request.get("redirectUri") == null
                ? null : String.valueOf(request.get("redirectUri"));
        return resultFactory.success(configService.googleAuthorizeUrl(configId, redirectUri));
    }

    @PostMapping("/email/google/callback")
    @LogAction(type = "邮箱授权完成")
    @PreAuthorize(CHANNEL_WRITE_PERMISSION)
    public Result googleCallback(@RequestBody java.util.Map<String, Object> request) {
        Long configId = request == null || request.get("configId") == null
                ? null : Long.valueOf(String.valueOf(request.get("configId")));
        String code = request == null || request.get("code") == null
                ? null : String.valueOf(request.get("code"));
        String state = request == null || request.get("state") == null
                ? null : String.valueOf(request.get("state"));
        String redirectUri = request == null || request.get("redirectUri") == null
                ? null : String.valueOf(request.get("redirectUri"));
        return resultFactory.success(configService.completeGoogleAuthorization(configId, code, state, redirectUri));
    }

    @PostMapping("/template/list")
    @PreAuthorize(TEMPLATE_READ_PERMISSION)
    public Result templateList() {
        return resultFactory.success(configService.templates());
    }

    /** 新增模板时也能独立读取可编辑语言，不要求翻译管理权限。 */
    @PostMapping("/template/languages")
    @PreAuthorize(TEMPLATE_READ_PERMISSION)
    public Result templateLanguages() {
        return resultFactory.success(configService.templateLanguages());
    }

    @PostMapping("/template/save")
    @PreAuthorize(TEMPLATE_WRITE_PERMISSION)
    @LogAction(type = "通知模板保存")
    public Result templateSave(@Valid @RequestBody NotificationTemplateSaveRequest request) {
        return resultFactory.success(configService.saveTemplate(request));
    }

    /** 在通知模板弹窗内直接把中文翻译到后台全部启用语言。 */
    @PostMapping("/template/translate")
    @LogAction(type = "通知模板翻译预览")
    @PreAuthorize(TEMPLATE_WRITE_PERMISSION)
    public Result templateTranslate(@Valid @RequestBody NotificationTemplateTranslateRequest request)
            throws InterruptedException {
        return resultFactory.success(templateTranslationService.translate(request));
    }

    @PostMapping("/template/delete")
    @PreAuthorize(TEMPLATE_DELETE_PERMISSION)
    @LogAction(type = "通知模板删除")
    public Result templateDelete(@Valid @RequestBody NotificationIdRequest request) {
        configService.deleteTemplate(request.id());
        return resultFactory.success();
    }
}
