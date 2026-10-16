package org.token.service.compute;

import org.token.config.SchedulingConfig;
import org.token.config.compute.ComputeMcagSchedulingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.assertj.core.api.Assertions.assertThat;

/** MCAG 开启后，其他业务任务仍使用原默认调度配置。 */
class ComputeMcagSchedulingTest {
    @Test void disabledSchedulingDoesNotRegisterAutomaticJobs() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingConfig.class, ComputeMcagSchedulingConfig.class)
                .withPropertyValues("exp.scheduling.enabled=false", "token.mcag.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor");
                    assertThat(context).doesNotHaveBean("computeMcagScheduler");
                });
    }

    @Test void originalSchedulingSettingsArePreserved() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingConfig.class, ComputeMcagSchedulingConfig.class)
                .withPropertyValues("token.mcag.enabled=true", "spring.task.scheduling.pool.size=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var original=context.getBean("taskScheduler",ThreadPoolTaskScheduler.class);
                    var gateway=context.getBean("computeMcagScheduler",ThreadPoolTaskScheduler.class);
                    assertThat(original).isNotSameAs(gateway);
                    assertThat(original.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
                    assertThat(gateway.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
                });
    }
}
