package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台用户主表
 */
@Data
@TableName("ad_master")
public class Account implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 UID
     */
    @TableId(value = "uid", type = IdType.AUTO)
    private Integer uid;

    /**
     * 登录密码
     *
     * 注意：
     * 新项目里这里保存 BCrypt hash，
     * 不再保存可逆加密密码。
     */
    @JsonIgnore
    private String password;

    /**
     * 用户名
     */
    private String username;

    /**
     * 角色 ID
     */
    private Integer role;

    /**
     * 账户状态
     *
     * 0 禁用
     * 1 正常
     */
    private Integer state;

    /**
     * 头像
     */
    private String avatar;

    /**
     * 标签
     */
    private String tag;

    /**
     * 用户语言
     */
    private String locale;

    /** 账号独立展示时区；为空时使用系统基础设置。 */
    @TableField("time_zone")
    private String timeZone;

    /** 联系电话、联系邮箱和账号资料，统一保存在账号主表。 */
    private String phone;
    private String contactEmail;
    private String remark;
    private String country;
    private Integer loginCount;
    private String lastLoginIp;
    private String lastLoginClient;
    /** 资料及登录摘要更新时间，不作为登录发生时间或安全失效边界。 */
    private LocalDateTime profileUpdatedAt;
}
