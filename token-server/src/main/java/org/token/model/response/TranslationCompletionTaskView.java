package org.token.model.response;

import java.time.LocalDateTime;

/** 管理端轮询使用的翻译补齐任务视图。 */
public record TranslationCompletionTaskView(
        Long taskId,
        Integer templateType,
        String templateName,
        String status,
        int progressPercent,
        int processedKeys,
        int totalKeys,
        int inserted,
        int translatedKeys,
        long missingBefore,
        long missingAfter,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
