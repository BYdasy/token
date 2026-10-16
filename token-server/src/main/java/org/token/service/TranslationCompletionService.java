package org.token.service;

import org.token.model.response.TranslationCompletionOverview;
import org.token.model.response.TranslationCompletionResult;
import org.token.model.response.TranslationCompletionProgress;

import java.util.function.Consumer;

/**
 * 按翻译模板统计并补齐启用语言。
 */
public interface TranslationCompletionService {

    TranslationCompletionOverview overview(Integer type);

    TranslationCompletionResult fillMissing(Integer type) throws InterruptedException;

    TranslationCompletionResult fillMissing(
            Integer type,
            Consumer<TranslationCompletionProgress> progressConsumer
    ) throws InterruptedException;
}
