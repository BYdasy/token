package org.token.service;

import org.token.model.response.TranslationCompletionTaskView;

/** 提交并查询持久化的翻译模板补齐任务。 */
public interface TranslationCompletionTaskService {

    TranslationCompletionTaskView submit(Integer templateType, Integer requestedBy);

    TranslationCompletionTaskView getTask(Long taskId, Integer requestedBy);
}
