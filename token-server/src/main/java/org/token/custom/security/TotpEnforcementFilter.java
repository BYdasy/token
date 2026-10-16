package org.token.custom.security;

import org.token.model.security.JwtUserPrincipal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.response.TotpStatusView;
import org.token.service.TotpService;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 要求验证器的账号执行写操作时必须已绑定，并且访问令牌最近通过过一次 TOTP。
 * 绑定/验证接口自身不会被本过滤器阻断。
 */
@Component
@RequiredArgsConstructor
public class TotpEnforcementFilter extends OncePerRequestFilter {

    private final TotpService totpService;
    private final ResultFactory resultFactory;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || uri.startsWith("/api/auth/totp/")
                || uri.equals("/api/auth/login")
                || uri.equals("/api/auth/logout");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtUserPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (principal.isTotpBindOnly()) {
            reject(response, request, 428, "server.auth.totp.bindRequired", true);
            return;
        }

        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        TotpStatusView status = totpService.status(principal.getUid());
        if (!status.required()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!status.bound()) {
            reject(response, request, 428, "server.auth.totp.bindRequired", true);
            return;
        }

        // 登录时通过过 TOTP 的标记在 JWT 整个有效期内有效。
        if (principal.getTotpVerifiedAt() != null && principal.getTotpVerifiedAt() > 0) {
            filterChain.doFilter(request, response);
            return;
        }

        // 管理员刚开启“要求验证器”的旧会话必须退出并重新登录，不接受请求头临时绕过。
        reject(response, request, 428, "server.auth.totp.reloginRequired", false);
    }

    private void reject(
            HttpServletResponse response,
            HttpServletRequest request,
            int code,
            String messageKey,
            boolean bindRequired
    ) throws IOException {
        response.setStatus(code);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Result result = resultFactory.create(
                code,
                java.util.Map.of(
                        "totpRequired", true,
                        "bindRequired", bindRequired
                ),
                messageKey
        );
        objectMapper.writeValue(response.getWriter(), result);
    }
}
