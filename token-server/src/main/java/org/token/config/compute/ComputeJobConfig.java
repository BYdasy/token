package org.token.config.compute;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 业务任务独立线程池，避免占用通知与翻译任务资源。 */
@Configuration
public class ComputeJobConfig {
    @Bean("computeJobExecutor")
    public ThreadPoolTaskExecutor computeJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("compute-job-"); executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20); executor.initialize(); return executor;
    }
}
