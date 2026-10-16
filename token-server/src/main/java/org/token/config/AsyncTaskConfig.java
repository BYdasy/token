package org.token.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 后台持久任务执行器。 */
@Configuration
public class AsyncTaskConfig {

    @Bean("translationCompletionTaskExecutor")
    public TaskExecutor translationCompletionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("translation-completion-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

    /** Telegram 多渠道短轮询并行执行，避免某个渠道的网络等待拖慢其他机器人。 */
    @Bean("telegramPollingTaskExecutor")
    public TaskExecutor telegramPollingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        // 定时轮询不需要排队；上一轮仍占满线程时直接丢弃本轮，避免陈旧任务堆积。
        executor.setQueueCapacity(0);
        // 必须通知调度器释放 active 标记；静默丢弃会使该渠道永久停轮询。
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("telegram-polling-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(3);
        executor.initialize();
        return executor;
    }
}
