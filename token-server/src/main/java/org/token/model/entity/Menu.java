package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 后台菜单与权限码。
 */
@Data
@TableName("memu")
public class Menu implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 菜单 ID，同时也是权限码。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;

    /**
     * 是否开启，1 开启，0 关闭。
     */
    @TableField("isopen")
    private Integer isOpen;

    /**
     * 菜单层级。
     */
    private Integer level;

    /**
     * 菜单名称。
     */
    private String name;

    /**
     * 父菜单 ID。
     */
    private Integer pid;

    /**
     * 排序值。
     */
    private Integer sort;

    /**
     * 子菜单，数据库不存在该字段。
     */
    @TableField(exist = false)
    private List<Menu> children;
}
