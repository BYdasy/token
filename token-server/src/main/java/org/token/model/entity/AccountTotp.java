package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理员安全记录的 TOTP 投影，其他安全字段由专用原子 SQL 更新。 */
@Data
@TableName("admin_security")
public class AccountTotp {
    @TableId(value = "admin_uid", type = IdType.INPUT)
    private Integer uid;
    @TableField("totp_secret_ciphertext")
    private String secretCiphertext;
    @TableField("totp_enabled")
    private Integer enabled;
    @TableField("totp_required")
    private Integer required;
    @TableField("totp_last_used_counter")
    private Long lastUsedCounter;
    @TableField("totp_confirmed_at")
    private LocalDateTime confirmedAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
