package org.token.service.impl;

import org.token.repository.dao.TranslationCompletionTaskMapper;
import org.token.repository.dao.AccountMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.token.model.entity.TranslationCompletionTask;
import org.token.model.entity.Account;
import org.token.model.response.TranslationCompletionOverview;
import org.token.model.response.TranslationCompletionResult;
import org.token.model.response.TranslationCompletionTaskView;
import org.token.exception.BusinessException;
import org.token.service.NotificationService;
import org.token.service.TranslationCompletionService;
import org.token.service.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationCompletionTaskServiceImplTest {

    @Mock
    private TranslationCompletionTaskMapper taskMapper;
    @Mock
    private TranslationCompletionService completionService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private TranslationService translationService;
    @Mock
    private AccountMapper accountMapper;
    @Mock
    private TaskExecutor taskExecutor;

    private TranslationCompletionTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace("org.exp.test.TranslationCompletionTaskMapper");
        TableInfoHelper.initTableInfo(assistant, TranslationCompletionTask.class);
        service = new TranslationCompletionTaskServiceImpl(new org.token.repository.system.TranslationCompletionTaskRepository(taskMapper), completionService, notificationService, translationService, new org.token.repository.system.AccountRepository(accountMapper), taskExecutor);
    }

    @Test
    void submitPersistsPendingTaskAndReturnsImmediately() {
        when(completionService.overview(5)).thenReturn(overview());
        doAnswer(invocation -> {
            TranslationCompletionTask task = invocation.getArgument(0);
            task.setId(91L);
            return 1;
        }).when(taskMapper).insert(any(TranslationCompletionTask.class));
        when(taskMapper.selectById(91L)).thenAnswer(invocation -> submittedTask(91L));

        TranslationCompletionTaskView view = service.submit(5, 7);

        assertEquals(91L, view.taskId());
        assertEquals("PENDING", view.status());
        verify(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void databaseUniqueGuardRejectsSecondActiveTaskForSameTemplate() {
        when(completionService.overview(5)).thenReturn(overview());
        when(taskMapper.insert(any(TranslationCompletionTask.class)))
                .thenThrow(new DuplicateKeyException("duplicate active template"));
        TranslationCompletionTask active = submittedTask(88L);
        active.setStatus("RUNNING");
        when(taskMapper.findActiveByType(any())).thenReturn(active);

        BusinessException error = assertThrows(BusinessException.class, () -> service.submit(5, 7));

        assertEquals("server.translation.task.alreadyRunning", error.getMessage());
        assertEquals(java.util.Map.of("taskId", 88L), error.getData());
    }

    @Test
    void backgroundExecutionPersistsSuccessAndSendsCompletionPopup() throws Exception {
        when(completionService.overview(5)).thenReturn(overview());
        doAnswer(invocation -> {
            TranslationCompletionTask task = invocation.getArgument(0);
            task.setId(92L);
            return 1;
        }).when(taskMapper).insert(any(TranslationCompletionTask.class));
        TranslationCompletionTask stored = submittedTask(92L);
        when(taskMapper.selectById(92L)).thenReturn(stored);
        when(taskMapper.start(any(),any())).thenReturn(1);
        when(taskMapper.complete(any(),any(),any())).thenReturn(1);
        TranslationCompletionResult result = new TranslationCompletionResult(
                5, "后台语言", 2, 10, 20, 20, 0, 10, 0, 3, 2, 3, 0);
        when(completionService.fillMissing(eq(5), any())).thenReturn(result);
        ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);

        service.submit(5, 7);
        verify(taskExecutor).execute(job.capture());
        job.getValue().run();

        verify(notificationService).notifyTranslationCompleted(result, 7);
        verify(taskMapper).start(eq(92L),any());
        verify(taskMapper).complete(eq(92L),eq(result),any());
    }

    @Test
    void startupMarksInterruptedTasksFailedAndNotifiesRequester() {
        TranslationCompletionTask running = submittedTask(93L);
        running.setStatus("RUNNING");
        when(taskMapper.findActive()).thenReturn(List.of(running));
        when(taskMapper.fail(any(),any(),any())).thenReturn(1);

        service.recoverInterruptedTasks();

        verify(notificationService).notifyTranslationFailed(
                "后台语言", "server.translation.task.interrupted", 7);
    }

    @Test
    void taskViewReturnsLocalizedErrorMessageAndStableErrorCode() {
        TranslationCompletionTask failed = submittedTask(94L);
        failed.setStatus("FAILED");
        failed.setErrorMessage("server.translation.task.interrupted");
        when(taskMapper.findOwned(any(),any())).thenReturn(failed);
        Account account = new Account();
        account.setUid(7);
        account.setLocale("zh");
        when(accountMapper.selectById(7)).thenReturn(account);
        when(translationService.getTranslate("server.translation.task.interrupted", "zh"))
                .thenReturn("服务重启或任务被中断，请重新提交");

        TranslationCompletionTaskView view = service.getTask(94L, 7);

        assertEquals("server.translation.task.interrupted", view.errorCode());
        assertEquals("服务重启或任务被中断，请重新提交", view.errorMessage());
    }

    private TranslationCompletionOverview overview() {
        return new TranslationCompletionOverview(5, "后台语言", 2, 10, 20, 17, 3, 7, 3);
    }

    private TranslationCompletionTask submittedTask(long id) {
        TranslationCompletionTask task = new TranslationCompletionTask();
        task.setId(id);
        task.setTemplateType(5);
        task.setTemplateName("后台语言");
        task.setRequestedBy(7);
        task.setStatus("PENDING");
        task.setProgressPercent(0);
        task.setProcessedKeys(0);
        task.setTotalKeys(10);
        task.setInserted(0);
        task.setTranslatedKeys(0);
        task.setMissingBefore(3L);
        task.setMissingAfter(3L);
        return task;
    }
}
