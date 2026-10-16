package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 已人工核实的 Telegram 接收目标，chatId 始终使用字符串保留负号和完整精度。 */
@Data
@TableName("notification_telegram_binding")
public class NotificationTelegramBinding {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("channel_id") private Long channelId;
    @TableField("chat_id") private String chatId;
    private String name;
    @TableField("chat_type") private String chatType;
    private Integer enabled;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
