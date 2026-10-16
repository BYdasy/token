package org.token.custom.security;

import org.token.model.security.JwtUserPrincipal;
import org.token.service.auth.AdminLoginSessionService;
import org.token.service.auth.LoginSessionPolicy;
import org.token.tools.security.SessionActivityFailureLog;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.token.model.entity.Account;
import org.token.model.entity.Role;
import org.token.service.AccountService;
import org.token.service.RoleService;
import org.token.tools.security.JWTUtil;
import org.token.tools.media.SundryUtils;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JWT 认证过滤器
 *
 * 逻辑：
 * 1. 从 Authorization header 或 Cookie 中获取 token
 * 2. 解析 uid / username
 * 3. 查询数据库用户
 * 4. 使用用户 password hash 校验 token
 * 5. 加载角色和权限
 * 6. 写入 Spring SecurityContext
 */
@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final AccountService accountService;

    private final RoleService roleService;

    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final AdminLoginSessionService loginSessions;
    private final SessionActivityFailureLog activityFailureLog = new SessionActivityFailureLog();
    private final SessionActivityFailureLog authenticationFailureLog = new SessionActivityFailureLog();

    /** 公开登录使用本次用户名、密码和 TOTP；旧请求头或兼容 Cookie 不能阻止重新登录。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        if (!HttpMethod.POST.matches(request.getMethod())) return false;
        String path = request.getServletPath();
        if (!StringUtils.hasText(path)) {
            path = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) path = path.substring(contextPath.length());
        }
        return "/api/auth/login".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = SundryUtils.getToken(request);

        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            authenticateByToken(token, request);
            new RequestAttributeSecurityContextRepository().saveContext(
                    SecurityContextHolder.getContext(), request, response);
        } catch (org.springframework.security.core.AuthenticationException e) {
            SecurityContextHolder.clearContext();
            jwtAuthenticationEntryPoint.commence(request, response, e);
            return;
        } catch (RuntimeException e) {
            SecurityContextHolder.clearContext();
            authenticationFailureLog.record(log, "后台认证依赖不可用 path: " + request.getRequestURI(), safeDiagnostic(e));
            jwtAuthenticationEntryPoint.unavailable(request, response);
            return;
        }
        // 下游业务异常交给 MVC/Security 原有处理；不能将流断开或业务错误误判为 JWT 失效。
        filterChain.doFilter(request, response);
        if (response.getStatus() < 400 && LoginSessionPolicy.isForegroundRequest(request)
                && SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof JwtUserPrincipal principal) {
            try { loginSessions.recordActivity(token, principal.getUid()); }
            catch (RuntimeException exception) {
                // 业务响应可能已经提交；续期故障保留告警，不重写响应或冒充凭据失效。
                activityFailureLog.record(log, "后台登录活动记录失败，响应保持原结果", exception);
            }
        }
    }

    /** 保留故障类型、数据库错误码和调用位置，不把可能含凭据的异常正文或 SQL 参数写入日志。 */
    private RuntimeException safeDiagnostic(RuntimeException failure) {
        StringBuilder detail = new StringBuilder("认证依赖错误类型：");
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (depth > 0) detail.append(" → ");
            detail.append(current.getClass().getSimpleName());
            if (current instanceof java.sql.SQLException sql) {
                detail.append("[SQLState=").append(sql.getSQLState()).append(", 错误码=").append(sql.getErrorCode()).append(']');
            }
        }
        IllegalStateException diagnostic = new IllegalStateException(detail.toString());
        diagnostic.setStackTrace(failure.getStackTrace());
        return diagnostic;
    }

    /**
     * 根据 token 完成认证。
     */
    private void authenticateByToken(String token, HttpServletRequest request) {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }

        String username = JWTUtil.getUsername(token);
        String uidText = JWTUtil.getUid(token);

        if (!StringUtils.hasText(username) || !StringUtils.hasText(uidText) || "0".equals(uidText)) {
            throw new BadCredentialsException("Token invalid");
        }

        Integer uid;

        try {
            uid = Integer.valueOf(uidText);
        } catch (NumberFormatException e) {
            throw new BadCredentialsException("Token uid invalid");
        }

        Account user = accountService.findByUid(uid);

        if (user == null) {
            throw new BadCredentialsException("User not found");
        }

        if (user.getState() != null && user.getState() == 0) {
            throw new DisabledException("User disabled");
        }

        if (!username.equals(user.getUsername())) {
            throw new BadCredentialsException("Token username invalid");
        }

        boolean verified = JWTUtil.verify(
                token,
                user.getUsername(),
                String.valueOf(user.getUid()),
                user.getPassword()
        );

        if (!verified) {
            throw new BadCredentialsException("Token verify failed");
        }
        if (!loginSessions.valid(token, user)) throw new BadCredentialsException("登录会话已过期或撤销");

        // 权限属于安全状态，鉴权时始终读取数据库最新角色，不能使用可能尚未失效的角色缓存。
        // 角色列表和普通详情仍可使用 RoleService 的缓存，不影响后台缓存功能。
        Role role = user.getRole() == null ? null : roleService.getById(user.getRole());

        Set<GrantedAuthority> authorities = buildAuthorities(user, role);

        JwtUserPrincipal principal = JwtUserPrincipal.from(user, authorities);
        principal.setTotpVerifiedAt(JWTUtil.getTotpVerifiedAt(token));
        principal.setTotpBindOnly(JWTUtil.isTotpBindOnly(token));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        authorities
                );

        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 构建权限。
     *
     * role ID 使用 ROLE_xxx 标识；角色权限码直接映射为 authority；
     * 超级角色额外拥有 super 权限。
     */
    private Set<GrantedAuthority> buildAuthorities(Account user, Role role) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();

        if (user.getRole() != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
        }

        if (role != null) {
            if (role.getIsSuper() != null && role.getIsSuper() == 1) {
                authorities.add(new SimpleGrantedAuthority("super"));
            }

            PermissionCodes.fromRole(role.getRole()).forEach(code ->
                    authorities.add(new SimpleGrantedAuthority(code)));
        }

        return authorities;
    }
}
