package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IP 白名单规则，支持精确地址和 CIDR。 */
@Data
@TableName("ip_whitelist")
public class IpWhitelistEntry {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String ipPattern;
    private String description;
    private Integer enabled;
    private Integer createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
