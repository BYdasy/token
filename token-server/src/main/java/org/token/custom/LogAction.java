package org.token.custom;

import java.lang.annotation.*;

/**
 * 操作日志注解
 *
 * 标记在 Controller 方法上。
 *
 * 例如：
 * @LogAction(type = "用户登入")
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LogAction {

    /**
     * 日志类型
     */
    String type();
}