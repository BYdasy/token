package org.token.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 翻译模板一键补齐任务的持久状态。 */
@Data
@TableName("translation_completion_task")
public class TranslationCompletionTask {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Integer templateType;
    private String templateName;
    private Integer requestedBy;
    private String status;
    private Integer progressPercent;
    private Integer processedKeys;
    private Integer totalKeys;
    private Integer inserted;
    private Integer translatedKeys;
    private Long missingBefore;
    private Long missingAfter;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
