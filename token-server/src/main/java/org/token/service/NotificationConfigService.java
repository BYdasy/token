package org.token.service;

import org.token.model.entity.NotificationTemplate;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.notification.NotificationConfigPageRequest;
import org.token.model.response.NotificationChannelOptionView;
import org.token.model.request.notification.ChannelConfigSaveRequest;
import org.token.model.request.notification.NotificationTemplateSaveRequest;
import org.token.model.response.NotificationChannelView;
import org.token.model.response.GoogleOAuthStartView;
import org.token.model.response.NotificationTemplateLanguageView;
import org.token.model.response.NotificationTemplateView;

import java.util.List;
import java.util.Map;

public interface NotificationConfigService {

    List<NotificationChannelView> channels();

    Page<NotificationChannelView> channelPage(NotificationConfigPageRequest request);

    List<NotificationChannelOptionView> channelOptions();

    NotificationChannelView saveChannel(ChannelConfigSaveRequest request);

    void deleteChannel(Long id);

    void testChannel(Long id, String recipients, String language);

    List<Map<String, Object>> telegramUpdates(Long id, String language);

    /** 自动轮询单个启用的 Telegram 渠道；渠道忙碌时跳过本轮。 */
    boolean pollTelegramChannel(Long id);

    /** 人工检查机器人身份与接收模式，不发送消息或删除 Webhook。 */
    NotificationChannelView inspectTelegram(Long id);

    GoogleOAuthStartView googleAuthorizeUrl(Long id, String redirectUri);

    NotificationChannelView completeGoogleAuthorization(Long id, String code, String state, String redirectUri);

    List<NotificationTemplateView> templates();

    Page<NotificationTemplateView> templatePage(NotificationConfigPageRequest request);

    NotificationTemplateView saveTemplate(NotificationTemplateSaveRequest request);

    List<NotificationTemplateLanguageView> templateLanguages();

    void deleteTemplate(Long id);

    NotificationTemplate findEnabledTemplate(String code);

    void sendExternal(Long configId, String title, String content, String recipients, String language);

    /** 已验证绑定的Telegram发送仍在最终派发时复核渠道类型，不能切换到其他外部渠道。 */
    void sendTelegramExternal(Long configId, String title, String content, String recipients, String language);
}
