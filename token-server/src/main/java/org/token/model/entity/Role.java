package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台角色表
 */
@Data
@TableName("user_role")
public class Role implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 角色名称
     */
    private String name;

    /**
     * 备注
     */
    private String remarks;

    /**
     * 操作时间。
     */
    @TableField("actionTime")
    private LocalDateTime actionTime;

    /**
     * 是否超级角色：1 是，0 否。
     */
    @TableField("isSelf")
    private Integer isSuper;

    /**
     * 权限码，多个用逗号分隔
     */
    private String role;
}
