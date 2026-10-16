package org.token.model.response;

/**
 * 模板缺失语言补齐结果。补齐是幂等的，只新增缺失记录。
 */
public record TranslationCompletionResult(
        Integer type,
        String templateName,
        int enabledLanguageCount,
        int keyCount,
        long expectedCount,
        long existingCount,
        long missingCount,
        int completeKeyCount,
        int incompleteKeyCount,
        int inserted,
        int translatedKeys,
        long missingBefore,
        long missingAfter
) {
}
