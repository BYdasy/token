package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 会员分组配置实体；授权与成员分配仍由业务服务管理。 */
@Data
@TableName(value="compute_member_group",autoResultMap=true)
public class ComputeMemberGroup {
    @TableId(value="id",type=IdType.AUTO)
    private Long id;
    private String name;
    @TableField("owner_member_id")
    private Long ownerMemberId;
    private String note;
    @TableField("sort_order")
    private Integer sortOrder;
    private Boolean enabled;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
