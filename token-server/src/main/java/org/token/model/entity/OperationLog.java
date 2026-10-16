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
 * 操作日志表
 */
@Data
@TableName("ad_record")
public class OperationLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 用户 UID
     */
    private Integer uid;

    /**
     * 用户名
     */
    private String username;

    /**
     * 登录 / 操作 IP
     */
    private String loginIp;

    /**
     * 登录 / 操作设备
     */
    private String clientos;

    /**
     * 操作时间
     *
     * 注意：
     * 操作时间。
     */
    @TableField("actiontime")
    private LocalDateTime actionTime;

    /**
     * 操作域名
     */
    private String domain;

    /**
     * IP 对应地址
     */
    private String address;

    /**
     * 状态
     *
     * 1 成功
     * 0 失败
     */
    private Integer status;

    /**
     * 备注
     */
    private String remarks;

    /**
     * 设备 ID
     */
    private String deviceId;

    /**
     * 日志类型
     *
     * 对应 @LogAction(type = "xxx")
     */
    private String type;
}
