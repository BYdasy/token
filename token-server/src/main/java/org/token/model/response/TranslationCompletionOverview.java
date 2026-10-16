package org.token.model.response;

/**
 * 一个翻译模板相对于当前启用语言配置的完整度。
 */
public record TranslationCompletionOverview(
        Integer type,
        String templateName,
        int enabledLanguageCount,
        int keyCount,
        long expectedCount,
        long existingCount,
        long missingCount,
        int completeKeyCount,
        int incompleteKeyCount
) {
}
