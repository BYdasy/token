package org.token.custom.security;

import org.token.service.compute.ComputeAccountService;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.tools.response.ResultFactory;
import org.token.custom.security.ClientIpResolver;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 仅在用户安全链中注册，禁止把用户凭据解释为管理员 JWT。 */
@lombok.extern.slf4j.Slf4j
public final class ComputeMemberAuthenticationFilter extends OncePerRequestFilter {
    private final ComputeAccountService accounts;
    private final ResultFactory results;
    private final ObjectMapper json;
    private final ClientIpResolver ipResolver;
    private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();
    private final org.token.tools.security.SessionActivityFailureLog activityFailureLog = new org.token.tools.security.SessionActivityFailureLog();
    private record Window(long start, int count) { }

    public ComputeMemberAuthenticationFilter(ComputeAccountService accounts, ResultFactory results, ObjectMapper json, ClientIpResolver ipResolver) {
        this.accounts = accounts; this.results = results; this.json = json; this.ipResolver = ipResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) { chain.doFilter(request, response); return; }
        if (request.getRequestURI().startsWith("/api/compute/auth/") && HttpMethod.POST.matches(request.getMethod())) {
            long now = System.currentTimeMillis();
            attempts.entrySet().removeIf(entry -> now - entry.getValue().start() > 600_000);
            String key = ipResolver.resolve(request);
            if (key == null || key.isBlank()) key = request.getRemoteAddr();
            if (key == null || key.isBlank()) key = "unknown";
            Window window = attempts.compute(key, (ignored, previous) -> previous == null
                    ? new Window(now, 1) : new Window(previous.start(), previous.count() + 1));
            if (window.count() > 60 || attempts.size() > 20_000) {
                response.setStatus(429); response.setContentType("application/json;charset=UTF-8");
                json.writeValue(response.getWriter(), results.failure(429, "server.compute.auth.tooMany", request));
                return;
            }
        }
        // 登录/注册不消费旧会话；过期凭据不能阻止用户重新登录或打开游客首页。
        String path = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
        if (path.startsWith("/api/compute/auth/") || "/api/compute/public/bootstrap".equals(path)
                || "/api/compute/public/home".equals(path)
                || ((HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod()))
                    && ("/api/compute/public/share".equals(path) || "/api/compute/public/share-image".equals(path)))
                || (HttpMethod.GET.matches(request.getMethod()) && ("/api/compute/public/legal/terms".equals(path)
                    || "/api/compute/public/legal/privacy".equals(path)))) {
            chain.doFilter(request, response); return;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            ComputeMemberPrincipal member = accounts.authenticate(header.substring(7));
            if (member == null) {
                SecurityContextHolder.clearContext();
                response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                json.writeValue(response.getWriter(), results.failure(401, "server.compute.session.expired", request));
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(member, null, List.of()));
        }
        chain.doFilter(request, response);
        if (response.getStatus() < 400 && header != null && header.startsWith("Bearer ")
                && org.token.service.auth.LoginSessionPolicy.isForegroundRequest(request)
                && SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof ComputeMemberPrincipal) {
            try { accounts.recordSessionActivity(header.substring(7)); }
            catch (RuntimeException exception) {
                activityFailureLog.record(log, "用户登录活动记录失败，响应保持原结果", exception);
            }
        }
    }
}
