package org.token.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.custom.LoggingAspect;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 全局异常处理
 *
 * Spring Boot 3 使用 jakarta.servlet。
 *
 * 统一处理认证、权限、参数校验和业务异常。
 * 新项目如果用 Spring Security，认证和权限异常分别处理：
 *
 * AuthenticationException  登录状态失效 / 未登录
 * AccessDeniedException    已登录但权限不足
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ResultFactory resultFactory;
    private final LoggingAspect audit;
    private final org.token.custom.security.ClientIpResolver clientIpResolver;

    /**
     * 未登录 / token 无效 / token 过期。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result> handleAuthenticationException(
            AuthenticationException e,
            HttpServletRequest request
    ) {
        log.warn("认证失败 path: {} ip: {} message: {}",
                request.getRequestURI(),
                getIp(request),
                e.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(resultFactory.failure(401, "server.session.expired"));
    }

    /**
     * 权限不足。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result> handleAccessDeniedException(
            AccessDeniedException e,
            HttpServletRequest request
    ) {
        log.warn("权限不足 path: {} ip: {} message: {}",
                request.getRequestURI(),
                getIp(request),
                e.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(resultFactory.failure(403, "server.session.no"));
    }

    /**
     * 业务异常。
     *
     * 例如：
     * throw new BusinessException("server.user.notFound");
     * throw new BusinessException(1001, "server.password.required");
     */
    @ExceptionHandler(BusinessException.class)
    public Result handleBusinessException(
            BusinessException e,
            HttpServletRequest request
    ) {
        log.warn("业务异常 path: {} ip: {} message: {}",
                request.getRequestURI(),
                getIp(request),
                e.getMessage()
        );

        return resultFactory.create(e.getCode(), e.getData(), e.getMessage());
    }

    /**
     * 参数校验异常。
     *
     * 例如：
     * @NotBlank(message = "server.username.required")
     * @Size(min = 6, message = "server.password.required")
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleValidException(
            MethodArgumentNotValidException e,
            HttpServletRequest request
    ) {
        audit.recordBindingFailure(request);
        String messageCode = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() == null ? "server.param.error" : error.getDefaultMessage())
                .orElse("server.param.error");

        log.warn("参数校验失败 path: {} ip: {} message: {}",
                request.getRequestURI(),
                getIp(request),
                messageCode
        );

        return resultFactory.failure(400, messageCode);
    }

    /** 绑定失败尚未进入控制器切面；不读取正文或拒绝值。 */
    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.validation.BindException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            jakarta.validation.ConstraintViolationException.class
    })
    public Result handleBindingException(Exception error, HttpServletRequest request) {
        audit.recordBindingFailure(request);
        log.warn("参数绑定失败，错误类别：{}", error.getClass().getSimpleName());
        return resultFactory.failure(400, "server.param.error");
    }

    /**
     * 请求超时异常。
     */
    @ExceptionHandler({
            SocketTimeoutException.class,
            TimeoutException.class,
            AsyncRequestTimeoutException.class,
            QueryTimeoutException.class
    })
    public ResponseEntity<Result> handleTimeoutException(
            Exception e,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        log.error("请求超时 path: {} ip: {}", request.getRequestURI(), getIp(request), e);
        if (!prepareJsonError(request, response)) return null;

        return ResponseEntity
                .status(HttpStatus.GATEWAY_TIMEOUT)
                .body(resultFactory.failure(504, "server.request.timeout"));
    }

    /** 普通 JSON 接口的客户端断连也会由 Spring 包装为响应不可写异常。 */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleUnusableResponse(AsyncRequestNotUsableException error,
                                      HttpServletRequest request, HttpServletResponse response) {
        // 容器已标记响应不可写，即使尚未提交，也不能再尝试序列化错误 JSON。
        if (ServletDisconnects.isClientDisconnect(error)) {
            log.debug("客户端连接已断开，停止写入响应 path: {}", request.getRequestURI());
        } else {
            // 不把所有异步写入故障都当作正常断连，未知原因仍保留堆栈。
            log.error("响应已不可写 path: {}", request.getRequestURI(), error);
        }
    }

    /** 缺失静态资源按 404 返回，不公开资源路径或记录系统故障。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleMissingResource(NoResourceFoundException error,
                                                     HttpServletResponse response) {
        // 无文件名或不存在的静态资源是正常 404，不暴露异常中的资源/文件系统路径。
        if (response.isCommitted()) return null;
        return ResponseEntity.notFound().build();
    }

    /** 其他真实故障保留日志，不把异常正文返回前端。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleException(
            Exception e,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        log.error("系统异常 path: {} ip: {}", request.getRequestURI(), getIp(request), e);
        if (!prepareJsonError(request, response)) return null;

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(resultFactory.failure(500, "server.error.internal"));
    }

    /** 所有已提交响应都不可二次写入 JSON；未识别的故障仍保留 ERROR。 */
    private boolean prepareJsonError(HttpServletRequest request, HttpServletResponse response) {
        if (response.isCommitted()) return false;
        if ("/api/system/notifications/my/stream".equals(request.getRequestURI()))
            response.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        return true;
    }

    /** 登录资料、白名单、操作审计与异常日志共用同一可信代理规则。 */
    private String getIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }
}
