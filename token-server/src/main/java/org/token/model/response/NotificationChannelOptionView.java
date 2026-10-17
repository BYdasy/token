package org.token.model.response;

/** 绑定管理的渠道选项，不查询或返回密钥、收件人及运行状态。 */
public record NotificationChannelOptionView(Long id, String name, String channelType) {
}
