package org.token.controller.admin;

import org.token.controller.admin.NotificationConfigController;
import org.token.controller.admin.NotificationController;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationPermissionTest {

    @Test void telegramSendingAndBindingAdministrationUseSeparateLeafPermissions() throws Exception {
        assertEquals("hasAnyAuthority('607','60702','601','60101','60102','super')", NotificationConfigController.class.getMethod("telegramSendOptions")
                .getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAnyAuthority('607','60702','super')", NotificationConfigController.class.getMethod("sendTelegramTemplate",
                        org.token.model.request.notification.TelegramTemplateSendRequest.class, org.token.model.security.JwtUserPrincipal.class)
                .getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAnyAuthority('607','60703','607031','super')", NotificationConfigController.class.getMethod("telegramBindings",
                        org.token.model.request.notification.TelegramBindingListRequest.class)
                .getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAnyAuthority('607','60703','607032','super')", NotificationConfigController.class.getMethod("saveTelegramBinding",
                        org.token.model.request.notification.TelegramBindingSaveRequest.class)
                .getAnnotation(PreAuthorize.class).value());
    }

    @Test
    void notificationPermissionsAreExplicitCodes() throws Exception {
        assertEquals("hasAnyAuthority('607','60703','607032','super')",
                NotificationConfigController.class.getMethod("inspectTelegram",
                        org.token.model.request.notification.NotificationIdRequest.class)
                        .getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAnyAuthority('607','60701','super')",
                NotificationController.class.getMethod("manageList", org.token.model.request.QueryParams.class)
                        .getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAnyAuthority('607','60702','super')",
                NotificationController.class.getMethod("send", org.token.model.security.JwtUserPrincipal.class,
                                org.token.model.request.notification.NotificationSendRequest.class)
                        .getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAnyAuthority('607','60702','super')",
                NotificationController.class.getMethod("targets")
                        .getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAnyAuthority('607','60703','607031','super')",
                NotificationConfigController.class.getMethod("channelList")
                        .getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAnyAuthority('607','60704','607041','super')",
                NotificationConfigController.class.getMethod("templateList")
                        .getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAnyAuthority('607','60702','60703','607032','super')",
                NotificationConfigController.class.getMethod("channelTest",
                                org.token.model.request.notification.ChannelTestRequest.class,
                                org.token.model.security.JwtUserPrincipal.class)
                        .getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAnyAuthority('607','60704','607042','super')",
                NotificationConfigController.class.getMethod("templateTranslate",
                                org.token.model.request.notification.NotificationTemplateTranslateRequest.class)
                        .getAnnotation(PreAuthorize.class).value());
    }
}
