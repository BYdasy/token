package org.token.service;

import org.token.model.entity.SystemNotification;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 站内通知实时推送，不承担历史记录存储。 */
public interface NotificationPushService {

    SseEmitter subscribe(Integer uid);

    void pushMessage(SystemNotification notification);

    void pushPopup(Integer uid, String title, String htmlContent);
    default void pushPopup(Integer uid,String title,String htmlContent,String severity){pushPopup(uid,title,htmlContent);}
    default void financeOrders(){ }
}
