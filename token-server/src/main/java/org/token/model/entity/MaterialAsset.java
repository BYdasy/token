package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("material_asset")
public class MaterialAsset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String mediaType;
    private String mimeType;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private Integer width;
    private Integer height;
    private Integer durationSeconds;
    private String category;
    private String tags;
    private String remark;
    private Integer createdBy;
    @TableLogic(value = "0", delval = "1")
    private Integer isDel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
