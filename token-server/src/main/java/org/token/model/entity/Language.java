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
 * 系统支持的多语言配置。
 */
@Data
@TableName("translate_language")
public class Language implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String lang;

    /** 英文名称；历史错位内容由独立增量修正，不通过状态编辑回写。 */
    private String name;

    /** 中文名称。 */
    @TableField("chinese_name")
    private String chineseName;

    /** 该语言的本地名称。 */
    @TableField("native_name")
    private String nativeName;

    @TableField("sort_no")
    private Integer sortNo;

    private Integer status;

    private String remark;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
