package org.token.config.compute;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 内容投递与资金结算分开使用有界工作队列。 */
@Configuration
public class ComputeContentExecutionConfig {
    @Bean("computeContentExecutor")
    public ThreadPoolTaskExecutor computeContentExecutor() {
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);executor.setMaxPoolSize(2);executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("compute-content-");executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
