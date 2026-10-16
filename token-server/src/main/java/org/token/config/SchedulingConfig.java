package org.token.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启系统后台定时任务。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "exp.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
