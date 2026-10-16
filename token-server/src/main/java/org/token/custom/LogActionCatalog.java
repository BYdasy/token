package org.token.custom;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 从当前实际存在的控制器方法中发现操作日志类型。
 *
 * 当前动作来自代码注解；查询入口再与历史记录动作合并，旧审计仍可筛选，
 * 尚未产生记录的新动作也能显示，不以手工列表代替实际接口。
 */
@Slf4j
@Component
public class LogActionCatalog {

    private static final String CONTROLLER_PACKAGE = "org.token";

    private final Set<String> supportedActions;

    public LogActionCatalog() {
        this.supportedActions = Collections.unmodifiableSet(discoverActions());
    }

    public Set<String> getSupportedActions() {
        return supportedActions;
    }

    private Set<String> discoverActions() {
        Set<String> actions = new LinkedHashSet<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        scanner.findCandidateComponents(CONTROLLER_PACKAGE).forEach(definition -> {
            String className = definition.getBeanClassName();
            if (className == null) {
                return;
            }
            try {
                Class<?> controller = ClassUtils.forName(className, getClass().getClassLoader());
                for (Method method : controller.getDeclaredMethods()) {
                    LogAction annotation = AnnotatedElementUtils.findMergedAnnotation(method, LogAction.class);
                    if (annotation != null && annotation.type() != null && !annotation.type().isBlank()) {
                        actions.add(annotation.type().trim());
                    }
                }
            } catch (Throwable error) {
                log.warn("读取控制器操作日志类型失败：{}", className, error);
            }
        });
        return actions;
    }
}
