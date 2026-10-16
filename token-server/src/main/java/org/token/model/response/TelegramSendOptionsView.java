package org.token.model.response;

import org.token.model.entity.NotificationTelegramBinding;
import java.util.List;

/** 仅发送权限可以获取的目标名称和编号，绝不包含渠道密钥或配置。 */
public record TelegramSendOptionsView(
        List<Channel> channels, List<NotificationTelegramBinding> bindings, List<Template> templates
) {
    public record Channel(Long id, String name) { }
    public record Template(String code, String shortCode, String name, List<String> variables) { }
}
