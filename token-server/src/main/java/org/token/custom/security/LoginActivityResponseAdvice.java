package org.token.custom.security;

import org.token.model.response.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 业务错误可能使用 HTTP 200，登录续期仍须依据统一 Result 的成功码。 */
@RestControllerAdvice
public class LoginActivityResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) { return true; }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter type, MediaType mediaType,
                                  Class<? extends HttpMessageConverter<?>> converter,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Result result && request instanceof ServletServerHttpRequest servlet) {
            servlet.getServletRequest().setAttribute("token.login.activity-success", Integer.valueOf(0).equals(result.getCode()));
        }
        return body;
    }
}
