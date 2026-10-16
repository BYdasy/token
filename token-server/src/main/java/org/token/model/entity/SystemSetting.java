package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

/** 数据库可维护的系统基础设置。 */
@Data
@TableName("system_setting")
public class SystemSetting {

    @TableId("setting_key")
    private String settingKey;
    @TableField("setting_value") private String settingValue;
    @TableField("value_type") private String valueType;
    @TableField("group_name") private String groupName;
    private String description;
    @TableField("is_public") private Integer isPublic;
    @TableField("is_editable") private Integer isEditable;
    @TableField("updated_by") private Integer updatedBy;
    private Long revision;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
