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
 * 账号主表的资料投影；不承担独立账号创建或删除。
 */
@Data
@TableName("ad_master")
public class AccountProfile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 UID
     */
    @TableId(value = "uid", type = IdType.INPUT)
    private Integer uid;

    /**
     * 用户名
     */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String username;

    /**
     * 保存的国家/地区摘要
     *
     * 数据库字段：country
     */
    @TableField(value = "country")
    private String countryName;

    /**
     * 客户端
     */
    @TableField("last_login_client")
    private String clientos;

    /**
     * 备注
     */
    @TableField("remark")
    private String comment;

    /**
     * 登录次数
     */
    private Integer loginCount;

    /**
     * 最后更新时间
     *
     * 数据库字段：profile_updated_at
     */
    @TableField("profile_updated_at")
    private LocalDateTime updateTime;

    /**
     * 最后登录 IP
     */
    @TableField("last_login_ip")
    private String loginIp;

}
