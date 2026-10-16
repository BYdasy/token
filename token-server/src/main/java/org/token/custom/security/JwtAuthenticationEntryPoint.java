package org.token.custom.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 401 处理
 *
 * 未登录、token 过期、token 无效都会进这里。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ResultFactory resultFactory;

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        writeFailure(request, response, HttpServletResponse.SC_UNAUTHORIZED, "server.session.expired");
    }

    /** 身份存储不可用时不能冒充凭据过期，避免前端清除刚签发的有效登录。 */
    public void unavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After", "5");
        writeFailure(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "server.error.internal");
    }

    private void writeFailure(HttpServletRequest request, HttpServletResponse response, int status,
                              String key) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Result result = resultFactory.failure(status, key, request);

        objectMapper.writeValue(response.getOutputStream(), result);
    }
}
