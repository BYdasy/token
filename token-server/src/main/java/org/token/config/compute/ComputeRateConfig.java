package org.token.config.compute;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 行情网络请求使用独立有界执行器，不阻塞 VIP 秒级结果调度。 */
@Configuration
public class ComputeRateConfig {
    @Bean("computeRateExecutor")
    public ThreadPoolTaskExecutor computeRateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("compute-rates-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
