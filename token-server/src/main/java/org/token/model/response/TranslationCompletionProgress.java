package org.token.model.response;

/** 补齐任务执行中的累计进度。 */
public record TranslationCompletionProgress(
        int processedKeys,
        int totalKeys,
        int inserted,
        int translatedKeys,
        long missingBefore
) {
}
