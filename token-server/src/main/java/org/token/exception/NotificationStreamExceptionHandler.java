package org.token.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.token.controller.admin.NotificationStreamController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/** 仅处理已经建立的通知流生命周期，JSON 接口仍交给统一异常处理。 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = NotificationStreamController.class)
public class NotificationStreamExceptionHandler {

    @ExceptionHandler({AsyncRequestTimeoutException.class, AsyncRequestNotUsableException.class})
    public ResponseEntity<Void> handleStreamLifecycle(Exception exception, HttpServletRequest request,
                                                     HttpServletResponse response) throws Exception {
        if (!"/api/system/notifications/my/stream".equals(request.getRequestURI())
                || !response.isCommitted()) {
            // 建流之前的错误仍是可诊断的服务异常，不能伪装成成功响应。
            if (!response.isCommitted()) response.setContentType(null);
            log.error("通知异步请求建立失败 path: {}", request.getRequestURI(), exception);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (exception instanceof AsyncRequestTimeoutException
                || ServletDisconnects.isClientDisconnect(exception)) {
            log.debug("通知流已断开或到期 path: {}", request.getRequestURI());
        } else {
            // 写入失败可能是未知容器/网络异常，保留真正故障，禁止再向已提交流写 Result。
            log.warn("通知流写入失败 path: {}", request.getRequestURI(), exception);
        }
        return null;
    }
}
