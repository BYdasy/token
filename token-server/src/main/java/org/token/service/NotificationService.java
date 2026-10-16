package org.token.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.token.model.entity.SystemNotification;
import org.token.model.request.notification.NotificationSendRequest;
import org.token.model.request.QueryParams;
import org.token.model.response.TranslationCompletionResult;
import org.token.model.response.NotificationTargetsView;

public interface NotificationService {

    IPage<SystemNotification> myNotifications(Integer uid, QueryParams params, boolean includeHidden);

    IPage<SystemNotification> manageNotifications(QueryParams params);

    long unreadCount(Integer uid);

    void markRead(Integer uid, Long id);

    void markAllRead(Integer uid);

    void hideForUser(Integer uid, Long id);

    void clearForUser(Integer uid);

    /** 发送通知时可选择的最小账号与角色字段，不要求账号/角色管理权限。 */
    NotificationTargetsView targets();

    int send(NotificationSendRequest request, Integer senderUid);

    /** 后端业务向指定用户发送 HTML 站内消息并持久化。 */
    void sendInternalMessage(Integer recipientUid, String title, String htmlContent, Integer senderUid);

    /** 后端业务向指定用户实时推送 HTML 弹窗，不写数据库。 */
    void sendPopup(Integer recipientUid, String title, String htmlContent);
    default void sendPopup(Integer recipientUid,String title,String htmlContent,String severity){sendPopup(recipientUid,title,htmlContent);}
    default void sendInternalMessage(Integer recipientUid,String title,String htmlContent,Integer senderUid,String severity){sendInternalMessage(recipientUid,title,htmlContent,senderUid);}
    default void notifyTelegramFailure(String channelName,Long channelId){ }

    /** 外部渠道异常时向所有超级管理员发送站内告警。 */
    void notifySuperAdmins(String title, String htmlContent);

    /** 可由翻译任务直接调用，向执行人发送完成弹窗。 */
    void notifyTranslationCompleted(TranslationCompletionResult result, Integer recipientUid);

    /** 翻译任务失败或被服务重启中断时，向执行人发送失败弹窗。 */
    void notifyTranslationFailed(String templateName, String errorKey, Integer recipientUid);
}
