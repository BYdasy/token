package org.token.exception;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 新业务使用明确的 HTTP 状态，支付通道不可用时不能以 HTTP 200 确认回调。 */
@RestControllerAdvice(annotations = org.token.custom.web.ComputeApi.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ComputeExceptionHandler {
    private final ResultFactory results;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> business(BusinessException error) {
        int status = error.getCode() != null && error.getCode() >= 400 && error.getCode() <= 599 ? error.getCode() : 400;
        return ResponseEntity.status(status).body(results.create(error.getCode(), error.getData(), error.getMessage()));
    }
}
