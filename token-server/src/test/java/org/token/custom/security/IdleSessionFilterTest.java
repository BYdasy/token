package org.token.custom.security;

import org.token.service.auth.AdminLoginSessionService;
import org.token.service.auth.LoginSessionPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.model.entity.Account;
import org.token.tools.response.ResultFactory;
import org.token.service.AccountService;
import org.token.service.RoleService;
import org.token.custom.security.ComputeMemberAuthenticationFilter;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputeAccountService;
import org.token.tools.security.JWTUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class IdleSessionFilterTest {
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
    private final java.util.Map<ch.qos.logback.classic.Logger, Boolean> previous = new java.util.LinkedHashMap<>();
    @BeforeEach
    void capture() {
        SecurityContextHolder.clearContext(); logs.start();
        for (Class<?> type : java.util.List.of(JwtAuthenticationFilter.class, ComputeMemberAuthenticationFilter.class)) {
            var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(type);
            previous.put(logger, logger.isAdditive()); logger.setAdditive(false); logger.addAppender(logs);
        }
    }
    @AfterEach
    void restore() {
        SecurityContextHolder.clearContext();
        previous.forEach((logger, additive) -> { logger.detachAppender(logs); logger.setAdditive(additive); }); logs.stop();
    }

    @Test
    void memberActivityStorageFailureDoesNotRewriteCommittedBusinessResponse() throws Exception {
        ComputeAccountService accounts = mock(ComputeAccountService.class);
        when(accounts.authenticate("member-token")).thenReturn(new ComputeMemberPrincipal(7, "member"));
        doThrow(new IllegalStateException("隔离测试模拟数据库故障")).when(accounts).recordSessionActivity("member-token");
        var filter = new ComputeMemberAuthenticationFilter(accounts, mock(ResultFactory.class), new ObjectMapper(), mock(ClientIpResolver.class));
        var request = request("/api/compute/user/wallets", "member-token"); var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { res.setCharacterEncoding("UTF-8"); res.getWriter().write("业务成功"); res.flushBuffer(); });
        assertEquals(200, response.getStatus()); assertEquals("业务成功", response.getContentAsString());
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR));
    }

    @Test
    void backgroundRequestStillAuthenticatesButCannotRenew() throws Exception {
        ComputeAccountService accounts = mock(ComputeAccountService.class);
        when(accounts.authenticate("member-token")).thenReturn(new ComputeMemberPrincipal(7, "member"));
        var filter = new ComputeMemberAuthenticationFilter(accounts, mock(ResultFactory.class), new ObjectMapper(), mock(ClientIpResolver.class));
        var request = request("/api/compute/user/wallets", "member-token"); request.addHeader("X-Background-Request", "true");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
        verify(accounts).authenticate("member-token"); verify(accounts, never()).recordSessionActivity(any());
        SecurityContextHolder.clearContext();
        when(accounts.authenticate("expired-token")).thenReturn(null);
        request = request("/api/compute/user/wallets", "expired-token"); request.addHeader("X-Background-Request", "true");
        var expired = new MockHttpServletResponse();
        filter.doFilter(request, expired, (req, res) -> fail("过期背景请求不能进入业务"));
        assertEquals(401, expired.getStatus());
    }

    @Test
    void adminActivityStorageFailureDoesNotBecomeAuthenticationFailure() throws Exception {
        AccountService accounts = mock(AccountService.class); AdminLoginSessionService sessions = mock(AdminLoginSessionService.class);
        JwtAuthenticationEntryPoint entry = mock(JwtAuthenticationEntryPoint.class);
        Account account = new Account(); account.setUid(1); account.setUsername("admin"); account.setPassword("hash"); account.setState(1);
        when(accounts.findByUid(1)).thenReturn(account); when(sessions.valid("token", account)).thenReturn(true);
        doThrow(new IllegalStateException("隔离测试模拟数据库故障")).when(sessions).recordActivity("token", 1);
        var filter = new JwtAuthenticationFilter(accounts, mock(RoleService.class), entry, sessions);
        try (var jwt = mockStatic(JWTUtil.class)) {
            jwt.when(() -> JWTUtil.getUid("token")).thenReturn("1"); jwt.when(() -> JWTUtil.getUsername("token")).thenReturn("admin");
            jwt.when(() -> JWTUtil.verify("token", "admin", "1", "hash")).thenReturn(true);
            var response = new MockHttpServletResponse();
            filter.doFilter(request("/api/system/settings", "token"), response, (req, res) -> { res.setCharacterEncoding("UTF-8"); res.getWriter().write("业务成功"); res.flushBuffer(); });
            assertEquals("业务成功", response.getContentAsString()); verifyNoInteractions(entry);
            assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR));
        }
    }

    @Test
    void unsuccessfulBusinessResultAndSseAreNotActivity() {
        var request = request("/api/compute/user/withdrawals", "token");
        request.setAttribute("token.login.activity-success", false); assertFalse(LoginSessionPolicy.isForegroundRequest(request));
        assertFalse(LoginSessionPolicy.isForegroundRequest(request("/api/system/notifications/my/stream", "token")));
        assertFalse(LoginSessionPolicy.isForegroundRequest(request("/api/compute/user/unread-count", "token")));
        assertFalse(LoginSessionPolicy.isForegroundRequest(request("/api/compute/admin/live-status", "token")));
        var normal = request("/api/compute/user/team", "token"); assertTrue(LoginSessionPolicy.isForegroundRequest(normal));
    }

    @Test
    void authenticationDatabaseFailureReturns503WithoutExposingCredentialOrCallingBusiness() throws Exception {
        AccountService accounts = mock(AccountService.class);
        AdminLoginSessionService sessions = mock(AdminLoginSessionService.class);
        ResultFactory results = mock(ResultFactory.class);
        when(results.failure(eq(503), eq("server.error.internal"), any())).thenReturn(new org.token.model.response.Result(503, null, "服务端异常"));
        var entry = new JwtAuthenticationEntryPoint(results, new ObjectMapper());
        Account account = new Account(); account.setUid(1); account.setUsername("admin"); account.setPassword("hash"); account.setState(1);
        when(accounts.findByUid(1)).thenReturn(account);
        var sqlFailure = new java.sql.SQLException("模拟敏感内容：private-token-hash", "HY000", 1267);
        when(sessions.valid("token", account)).thenThrow(new org.springframework.jdbc.BadSqlGrammarException("credential", "不可公开SQL", sqlFailure));
        var filter = new JwtAuthenticationFilter(accounts, mock(RoleService.class), entry, sessions);
        try (var jwt = mockStatic(JWTUtil.class)) {
            jwt.when(() -> JWTUtil.getUid("token")).thenReturn("1"); jwt.when(() -> JWTUtil.getUsername("token")).thenReturn("admin");
            jwt.when(() -> JWTUtil.verify("token", "admin", "1", "hash")).thenReturn(true);
            var response = new MockHttpServletResponse();
            filter.doFilter(request("/api/auth/codes", "token"), response, (req, res) -> fail("身份存储故障不能进入业务"));
            assertEquals(503, response.getStatus()); assertEquals("5", response.getHeader("Retry-After"));
            assertFalse(response.getContentAsString().contains("private-token-hash"));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            var error = logs.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR).findFirst().orElseThrow();
            assertTrue(error.getThrowableProxy().getMessage().contains("1267"));
            assertFalse(error.getThrowableProxy().getMessage().contains("private-token-hash"));
            assertNull(error.getThrowableProxy().getCause());
        }
    }

    @Test
    void genuinelyInvalidAdminCredentialStillReturns401() throws Exception {
        ResultFactory results = mock(ResultFactory.class);
        when(results.failure(eq(401), eq("server.session.expired"), any())).thenReturn(new org.token.model.response.Result(401, null, "登录失效"));
        var filter = new JwtAuthenticationFilter(mock(AccountService.class), mock(RoleService.class),
                new JwtAuthenticationEntryPoint(results, new ObjectMapper()), mock(AdminLoginSessionService.class));
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/auth/codes", "malformed"), response, (req, res) -> fail("无效凭据不能进入业务"));
        assertEquals(401, response.getStatus());
        assertTrue(logs.list.stream().noneMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR));
    }

    @Test
    void publicPostLoginIgnoresStaleHeaderAndCookieWithContextPathWhileBusinessStillAuthenticates() throws Exception {
        AccountService accounts = mock(AccountService.class); RoleService roles = mock(RoleService.class);
        AdminLoginSessionService sessions = mock(AdminLoginSessionService.class);
        ResultFactory results = mock(ResultFactory.class);
        when(results.failure(eq(401), eq("server.session.expired"), any())).thenReturn(new org.token.model.response.Result(401, null, "登录失效"));
        var filter = new JwtAuthenticationFilter(accounts, roles, new JwtAuthenticationEntryPoint(results, new ObjectMapper()), sessions);
        var login = new MockHttpServletRequest("POST", "/context/api/auth/login"); login.setContextPath("/context");
        login.addHeader("Authorization", "Bearer stale-header");
        login.setCookies(new jakarta.servlet.http.Cookie("Authorization", "stale-cookie"));
        java.util.concurrent.atomic.AtomicBoolean reachedLogin = new java.util.concurrent.atomic.AtomicBoolean();
        var loginResponse = new MockHttpServletResponse();
        filter.doFilter(login, loginResponse, (req, res) -> reachedLogin.set(true));
        assertTrue(reachedLogin.get()); assertEquals(200, loginResponse.getStatus());
        verifyNoInteractions(accounts, roles, sessions, results);

        for (boolean includeHeader : java.util.List.of(true, false)) {
            var business = new MockHttpServletRequest("GET", "/context/api/auth/codes"); business.setContextPath("/context");
            business.setCookies(new jakarta.servlet.http.Cookie("Authorization", "stale-cookie"));
            if (includeHeader) business.addHeader("Authorization", "Bearer stale-header");
            var response = new MockHttpServletResponse();
            filter.doFilter(business, response, (req, res) -> fail("业务接口必须继续校验旧凭据"));
            assertEquals(401, response.getStatus());
        }
    }

    private MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path); request.addHeader("Authorization", "Bearer " + token); return request;
    }
}
