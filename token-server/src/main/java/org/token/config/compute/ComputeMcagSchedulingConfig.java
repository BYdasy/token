package org.token.config.compute;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 支付事件、地址监控、提款调度互不占用线程，也不影响既有 VIP、奖励和消息任务。 */
@Configuration
@ConditionalOnProperty(name = "token.mcag.enabled", havingValue = "true")
public class ComputeMcagSchedulingConfig {
    /** 保留原任务的默认调度器及配置，避免新增 MCAG 线程后导致自动配置退让。 */
    @Bean("taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler defaultTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean("computeMcagScheduler")
    public ThreadPoolTaskScheduler computeMcagScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3); scheduler.setThreadNamePrefix("mcag-payment-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
