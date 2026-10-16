package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内通知。每个接收账号保存一条记录，便于分别维护已读状态。
 */
@Data
@TableName("system_notification")
public class SystemNotification implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer recipientUid;

    private Integer senderUid;

    /** 展示名称由分页结果批量补充，不作为数据库字段保存。 */
    @TableField(exist = false)
    private String recipientUsername;

    @TableField(exist = false)
    private String senderUsername;

    private String title;

    private String content;

    /** INFO/SUCCESS/WARNING/ERROR；历史记录默认普通提示。 */
    private String severity;

    private Integer readStatus;

    private LocalDateTime readTime;

    /** 1 表示已被接收用户从默认通知列表隐藏，历史列表仍可查看。 */
    private Integer hiddenStatus;

    private LocalDateTime createdAt;
}
