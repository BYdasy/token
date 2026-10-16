package org.token.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.token.repository.system.TranslationCompletionTaskRepository;
import org.token.repository.system.AccountRepository;
import org.token.model.entity.Account;
import org.token.model.entity.TranslationCompletionTask;
import org.token.model.response.TranslationCompletionOverview;
import org.token.model.response.TranslationCompletionProgress;
import org.token.model.response.TranslationCompletionResult;
import org.token.model.response.TranslationCompletionTaskView;
import org.token.exception.BusinessException;
import org.token.service.NotificationService;
import org.token.service.TranslationCompletionService;
import org.token.service.TranslationCompletionTaskService;
import org.token.service.TranslationService;
import org.token.tools.media.SundryUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 翻译模板一键补齐的持久任务调度器。 */
@Slf4j
@Service
public class TranslationCompletionTaskServiceImpl implements TranslationCompletionTaskService {

    static final String PENDING = "PENDING";
    static final String RUNNING = "RUNNING";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";

    private final TranslationCompletionTaskRepository taskRepository;
    private final TranslationCompletionService completionService;
    private final NotificationService notificationService;
    private final TranslationService translationService;
    private final AccountRepository accountRepository;
    private final TaskExecutor taskExecutor;

    public TranslationCompletionTaskServiceImpl(
            TranslationCompletionTaskRepository taskRepository,
            TranslationCompletionService completionService,
            NotificationService notificationService,
            TranslationService translationService,
            AccountRepository accountRepository,
            @Qualifier("translationCompletionTaskExecutor") TaskExecutor taskExecutor) {
        this.taskRepository = taskRepository;
        this.completionService = completionService;
        this.notificationService = notificationService;
        this.translationService = translationService;
        this.accountRepository = accountRepository;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public TranslationCompletionTaskView submit(Integer templateType, Integer requestedBy) {
        requireUid(requestedBy);
        TranslationCompletionOverview overview = completionService.overview(templateType);
        LocalDateTime now = SundryUtils.getTime();

        TranslationCompletionTask task = new TranslationCompletionTask();
        task.setTemplateType(overview.type());
        task.setTemplateName(overview.templateName());
        task.setRequestedBy(requestedBy);
        task.setStatus(PENDING);
        task.setProgressPercent(0);
        task.setProcessedKeys(0);
        task.setTotalKeys(overview.keyCount());
        task.setInserted(0);
        task.setTranslatedKeys(0);
        task.setMissingBefore(overview.missingCount());
        task.setMissingAfter(overview.missingCount());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        try {
            if (!taskRepository.save(task)) {
                throw new BusinessException("server.translation.task.submitFailed");
            }
        } catch (DuplicateKeyException exception) {
            TranslationCompletionTask active = findActive(templateType);
            throw new BusinessException(
                    -1,
                    active == null ? Map.of() : Map.of("taskId", active.getId()),
                    "server.translation.task.alreadyRunning"
            );
        }

        try {
            taskExecutor.execute(() -> executeTask(task.getId()));
        } catch (RuntimeException exception) {
            failTask(task, "server.translation.task.submitFailed");
            throw new BusinessException("server.translation.task.submitFailed");
        }
        return toView(taskRepository.getById(task.getId()));
    }

    @Override
    public TranslationCompletionTaskView getTask(Long taskId, Integer requestedBy) {
        requireUid(requestedBy);
        if (taskId == null || taskId <= 0) {
            throw new BusinessException("server.translation.task.notFound");
        }
        TranslationCompletionTask task = taskRepository.findOwned(taskId, requestedBy);
        if (task == null) {
            throw new BusinessException("server.translation.task.notFound");
        }
        return toView(task);
    }

    /** 应用异常退出时无法继续内存中的任务，启动后明确标记失败并允许重新提交。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        try {
            List<TranslationCompletionTask> activeTasks = taskRepository.findActive();
            for (TranslationCompletionTask task : activeTasks) {
                if (failTask(task, "server.translation.task.interrupted")) {
                    notifyFailed(task, "server.translation.task.interrupted");
                }
            }
        } catch (Exception exception) {
            // 增量 SQL 尚未执行时不阻断应用启动。
            log.warn("恢复翻译补齐任务失败，请确认 translation_completion_task 表已创建：{}", exception.getMessage());
        }
    }

    private void executeTask(Long taskId) {
        TranslationCompletionTask task = taskRepository.getById(taskId);
        if (task == null || !startTask(task)) {
            return;
        }
        try {
            TranslationCompletionResult result = completionService.fillMissing(
                    task.getTemplateType(),
                    progress -> updateProgress(taskId, progress)
            );
            completeTask(task, result);
            try {
                notificationService.notifyTranslationCompleted(result, task.getRequestedBy());
            } catch (Exception exception) {
                log.warn("翻译补齐任务已完成，但完成通知发送失败，任务ID：{}", taskId, exception);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failAndNotify(task, "server.translation.task.interrupted");
        } catch (Exception exception) {
            log.warn("翻译补齐任务执行失败，任务ID：{}", taskId, exception);
            failAndNotify(task, safeErrorKey(exception));
        }
    }

    private boolean startTask(TranslationCompletionTask task) {
        LocalDateTime now = SundryUtils.getTime();
        int updated = taskRepository.start(task.getId(),now);
        if (updated == 1) {
            task.setStatus(RUNNING);
            task.setStartedAt(now);
            return true;
        }
        return false;
    }

    private void updateProgress(Long taskId, TranslationCompletionProgress progress) {
        int percent = progress.totalKeys() <= 0
                ? 0 : Math.min(99, progress.processedKeys() * 100 / progress.totalKeys());
        taskRepository.progress(taskId,percent,progress,SundryUtils.getTime());
    }

    private void completeTask(TranslationCompletionTask task, TranslationCompletionResult result) {
        LocalDateTime now = SundryUtils.getTime();
        int updated = taskRepository.complete(task.getId(),result,now);
        if (updated != 1) {
            throw new BusinessException("server.translation.task.updateFailed");
        }
    }

    private void failAndNotify(TranslationCompletionTask task, String errorKey) {
        if (failTask(task, errorKey)) {
            notifyFailed(task, errorKey);
        }
    }

    private boolean failTask(TranslationCompletionTask task, String errorKey) {
        LocalDateTime now = SundryUtils.getTime();
        return taskRepository.fail(task.getId(),truncate(errorKey,1000),now) == 1;
    }

    private void notifyFailed(TranslationCompletionTask task, String errorKey) {
        try {
            notificationService.notifyTranslationFailed(
                    task.getTemplateName(), errorKey, task.getRequestedBy());
        } catch (Exception exception) {
            log.warn("翻译补齐任务失败通知发送失败，任务ID：{}", task.getId(), exception);
        }
    }

    private TranslationCompletionTask findActive(Integer templateType) {
        return taskRepository.findActiveByType(templateType);
    }

    private String safeErrorKey(Exception exception) {
        if (exception instanceof BusinessException businessException
                && StringUtils.hasText(businessException.getMessage())
                && businessException.getMessage().startsWith("server.")) {
            return businessException.getMessage();
        }
        return "server.translation.task.failed";
    }

    private TranslationCompletionTaskView toView(TranslationCompletionTask task) {
        if (task == null) {
            throw new BusinessException("server.translation.task.notFound");
        }
        return new TranslationCompletionTaskView(
                task.getId(), task.getTemplateType(), task.getTemplateName(), task.getStatus(),
                value(task.getProgressPercent()), value(task.getProcessedKeys()), value(task.getTotalKeys()),
                value(task.getInserted()), value(task.getTranslatedKeys()), value(task.getMissingBefore()),
                value(task.getMissingAfter()), task.getErrorMessage(), localizedError(task),
                task.getCreatedAt(), task.getStartedAt(),
                task.getFinishedAt()
        );
    }

    private String localizedError(TranslationCompletionTask task) {
        String errorKey = task.getErrorMessage();
        if (!StringUtils.hasText(errorKey) || !errorKey.startsWith("server.")) {
            return errorKey;
        }
        try {
            Account account = accountRepository.getById(task.getRequestedBy());
            return translationService.getTranslate(errorKey, account == null ? null : account.getLocale());
        } catch (Exception exception) {
            return errorKey;
        }
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private void requireUid(Integer uid) {
        if (uid == null || uid <= 0) {
            throw new BusinessException("server.user.uidRequired");
        }
    }

    private String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
