package org.token.config;

import org.junit.jupiter.api.Tag;

import jakarta.servlet.FilterChain;
import org.token.controller.admin.NotificationStreamController;
import org.token.model.entity.Account;
import org.token.tools.response.ResultFactory;
import org.token.custom.security.*;
import org.token.service.*;
import org.token.service.auth.AdminLoginSessionService;
import org.token.service.impl.NotificationPushServiceImpl;
import org.token.custom.web.RequestTimeZoneFilter;
import org.token.tools.security.JWTUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 用真实安全链验证同一次 SSE 异步恢复，不给其他请求或匿名访问放行。 */
@WebMvcTest(NotificationStreamController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, NotificationPushServiceImpl.class})
@Tag("regression")
class NotificationStreamSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired NotificationPushServiceImpl pushes;
    @MockitoBean NotificationService notifications;
    @MockitoBean ResultFactory results;
    @MockitoBean EnabledLocaleResolver locales;
    @MockitoBean AccountService accounts;
    @MockitoBean AdminLoginSessionService loginSessions;
    @MockitoBean RoleService roles;
    @MockitoBean JwtAuthenticationEntryPoint entryPoint;
    @MockitoBean JwtAccessDeniedHandler deniedHandler;
    @MockitoBean IpWhitelistFilter whitelist;
    @MockitoBean TotpEnforcementFilter totp;
    @MockitoBean RequestTimeZoneFilter timeZone;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean org.token.custom.LoggingAspect logging;

    @BeforeEach
    void filters() throws Exception {
        org.mockito.stubbing.Answer<Void> pass = invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        };
        doAnswer(pass).when(whitelist).doFilter(any(), any(), any());
        doAnswer(pass).when(totp).doFilter(any(), any(), any());
        doAnswer(pass).when(timeZone).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            jakarta.servlet.http.HttpServletResponse response = invocation.getArgument(1);
            response.setStatus(401); return null;
        }).when(entryPoint).commence(any(), any(), any());
        doAnswer(invocation -> {
            jakarta.servlet.http.HttpServletResponse response = invocation.getArgument(1);
            response.setStatus(403); return null;
        }).when(deniedHandler).handle(any(), any(), any());
    }

    @Test
    void anonymousStreamStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/system/notifications/my/stream"))
                .andExpect(status().isUnauthorized()).andExpect(request().asyncNotStarted());
    }

    @Test
    void completionRestoresAuthenticatedRequestWithoutSecondAuthorizationFailure() throws Exception {
        Account account = new Account();
        account.setUid(17); account.setUsername("operator"); account.setPassword("hash"); account.setState(1);
        when(accounts.findByUid(17)).thenReturn(account);
        when(loginSessions.valid("test-token", account)).thenReturn(true);
        when(loginSessions.validDigest(any(), org.mockito.ArgumentMatchers.eq(17))).thenReturn(true);
        try (var jwt = mockStatic(JWTUtil.class)) {
            jwt.when(() -> JWTUtil.getUsername("test-token")).thenReturn("operator");
            jwt.when(() -> JWTUtil.getUid("test-token")).thenReturn("17");
            jwt.when(() -> JWTUtil.verify("test-token", "operator", "17", "hash")).thenReturn(true);
            var connected = mvc.perform(get("/api/system/notifications/my/stream")
                            .header("Authorization", "Bearer test-token"))
                    .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
            pushes.heartbeat();
            assertTrue(connected.getResponse().getContentAsString().contains("heartbeat"));
            when(loginSessions.validDigest(any(), org.mockito.ArgumentMatchers.eq(17))).thenReturn(false);
            pushes.pushPopup(17, "不能泄露的通知", "退出后禁止发送");
            assertFalse(connected.getResponse().getContentAsString().contains("不能泄露的通知"));
            mvc.perform(asyncDispatch(connected)).andExpect(status().isOk());
            verify(entryPoint, never()).commence(any(), any(), any());
            verify(deniedHandler, never()).handle(any(), any(), any());
            verify(accounts, times(1)).findByUid(17);
        }
    }
}
