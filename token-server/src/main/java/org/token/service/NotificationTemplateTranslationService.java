package org.token.service;

import org.token.model.request.notification.NotificationTemplateTranslateRequest;
import org.token.model.response.NotificationTemplateTranslationResult;

/** 通知模板页面专用翻译服务，不依赖翻译管理页面权限。 */
public interface NotificationTemplateTranslationService {

    NotificationTemplateTranslationResult translate(NotificationTemplateTranslateRequest request)
            throws InterruptedException;
}
