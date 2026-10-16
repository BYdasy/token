package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 外部通知渠道配置。敏感 JSON 只通过脱敏视图返回。 */
@Data
@TableName("notification_channel_config")
public class NotificationChannelConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String channelType;

    private Integer enabled;

    @JsonIgnore
    private String encryptedConfig;

    private String defaultRecipients;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 最近一次 Telegram 轮询失败告警时间，用于跨重启 24 小时限频。 */
    private LocalDateTime lastFailureAlertAt;
}
