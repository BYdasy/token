package org.token.custom;

import org.aspectj.lang.ProceedingJoinPoint;
import org.token.model.entity.OperationLog;
import org.token.model.response.Result;
import org.token.exception.BusinessException;
import org.token.custom.security.ClientIpResolver;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.OperationLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.HandlerMethod;
import org.token.exception.GlobalExceptionHandler;
import org.token.tools.response.ResultFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实切面流程只连接内存请求和 mock 日志服务，不连接数据库或通知。 */
class LoggingAspectTest {
    private OperationLogService logs;
    private LoggingAspect aspect;
    private MockHttpServletRequest request;
    private LogAction action;

    @LogAction(type = "测试配置")
    void audited() { }

    @BeforeEach
    void setup() throws Exception {
        logs = mock(OperationLogService.class);
        aspect = new LoggingAspect(logs, new ClientIpResolver("127.0.0.1"));
        action = getClass().getDeclaredMethod("audited").getAnnotation(LogAction.class);
        request = new MockHttpServletRequest("POST", "/api/compute/admin/config/9");
        request.setRemoteAddr("198.51.100.9");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        request.addHeader("Referer", "https://example.test/?token=REFERER_SECRET");
        request.addHeader("Host", "example.test");
        request.addHeader("Authorization", "Bearer FORGED_SECRET");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/compute/admin/config/{id}");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "9"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                JwtUserPrincipal.builder().uid(8).username("operator").build(), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void writesUnmarkedSuccessWithAuthenticatedActorAndOnlyMetadata() throws Throwable {
        var point = point(Map.of("id", 9, "amount", "12.50", "password", "PASSWORD_SECRET",
                "otpCode", "123456", "content", "PRIVATE_BODY", "apiKey", "KEY_SECRET"));
        Result returned = new Result(Map.of("id", 9, "accessToken", "TOKEN_SECRET", "secret", "TOTP_SECRET"));
        when(point.proceed()).thenReturn(returned);

        assertSame(returned, aspect.logAroundMethod(point, action));

        OperationLog row = row();
        assertEquals(1, row.getStatus());
        assertEquals(8, row.getUid());
        assertEquals("operator", row.getUsername());
        assertEquals("198.51.100.9", row.getLoginIp());
        assertEquals("example.test", row.getDomain());
        assertTrue(row.getRemarks().contains("id=9"));
        assertTrue(row.getRemarks().contains("amount=12.50"));
        for (String secret : List.of("PASSWORD_SECRET", "123456", "PRIVATE_BODY", "KEY_SECRET", "TOKEN_SECRET", "TOTP_SECRET", "FORGED_SECRET", "REFERER_SECRET")) {
            assertFalse(row.toString().contains(secret), secret);
        }
    }

    @Test
    void failureResultOverridesCustomSuccessAndDoesNotCopyResponseMessage() throws Throwable {
        var point = point();
        when(point.proceed()).thenAnswer(call -> {
            LoggingAspect.setLogDetails("完成申请", 1);
            return new Result(-1, Map.of("token", "SECRET"), "SQL SELECT PRIVATE_BODY");
        });
        aspect.logAroundMethod(point, action);
        assertEquals(0, row().getStatus());
        assertTrue(row().getRemarks().contains("返回码：-1"));
        assertFalse(row().getRemarks().contains("PRIVATE_BODY"));
    }

    @Test
    void httpFailureAndBusinessExceptionHaveFailureStatus() throws Throwable {
        var point = point();
        when(point.proceed()).thenReturn(ResponseEntity.status(403).body(new Result()));
        aspect.logAroundMethod(point, action);
        assertEquals(0, row().getStatus());
        clearInvocations(logs);
        BusinessException refusal = new BusinessException("server.compute.invalidInput");
        when(point.proceed()).thenThrow(refusal);
        assertSame(refusal, assertThrows(BusinessException.class, () -> aspect.logAroundMethod(point, action)));
        assertTrue(row().getRemarks().contains("server.compute.invalidInput"));
    }

    @Test
    void unexpectedExceptionNeverSerializesItsMessageOrReplacesBusinessException() throws Throwable {
        var point = point();
        IllegalStateException failure = new IllegalStateException("SQL values password=SECRET privateKey=KEY");
        when(point.proceed()).thenThrow(failure);
        doThrow(new IllegalStateException("DB_SECRET")).when(logs).insert(any());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> aspect.logAroundMethod(point, action)));
        assertEquals(0, row().getStatus());
        assertFalse(row().getRemarks().contains("SECRET"));
        assertTrue(row().getRemarks().contains("IllegalStateException"));
    }

    @Test
    void logFailureDoesNotTurnSuccessfulMutationIntoRetryableError() throws Throwable {
        var point = point();
        Result returned = new Result();
        when(point.proceed()).thenReturn(returned);
        doThrow(new IllegalStateException("log store offline")).when(logs).insert(any());
        assertSame(returned, aspect.logAroundMethod(point, action));
    }

    @Test
    void invocationStateCannotLeakAndNestedAuditRestoresOuterDetails() throws Throwable {
        LoggingAspect.setLogDetails("OUTSIDE_CALL_SHOULD_NOT_LEAK", 0);
        var inner = point();
        when(inner.proceed()).thenAnswer(call -> {
            LoggingAspect.setLogDetails("INNER_CALL", 1);
            return new Result();
        });
        var outer = point();
        when(outer.proceed()).thenAnswer(call -> {
            LoggingAspect.setLogDetails("OUTER_CALL", 1);
            aspect.logAroundMethod(inner, action);
            return new Result();
        });
        aspect.logAroundMethod(outer, action);
        var capture = ArgumentCaptor.forClass(OperationLog.class);
        verify(logs, times(2)).insert(capture.capture());
        assertTrue(capture.getAllValues().get(0).getRemarks().contains("INNER_CALL"));
        assertTrue(capture.getAllValues().get(1).getRemarks().contains("OUTER_CALL"));
        assertFalse(capture.getAllValues().get(1).getRemarks().contains("INNER_CALL"));
        assertTrue(capture.getAllValues().stream().allMatch(row -> row.getStatus() == 1 && !row.getRemarks().contains("SHOULD_NOT_LEAK")));
    }

    @Test
    void anonymousLoginStoresOnlyExplicitAttemptedUsername() throws Throwable {
        SecurityContextHolder.clearContext();
        var point = point();
        when(point.proceed()).thenAnswer(call -> {
            LoggingAspect.setAuthLogDetails("后台登录验证", 0, "operator");
            return new Result(4602, Map.of("accessToken", "BIND_SECRET"));
        });
        aspect.logAroundMethod(point, action);
        assertNull(row().getUid());
        assertEquals("operator", row().getUsername());
        assertFalse(row().getRemarks().contains("BIND_SECRET"));
    }

    @Test
    void bindingFailureAuditsAuthenticatedAnnotatedHandlerWithoutReadingInvalidBody() throws Exception {
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(this, getClass().getDeclaredMethod("audited")));
        request.setContent("{\"password\":\"BODY_SECRET\",\"amount\":\"INVALID_SECRET\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var handler = new GlobalExceptionHandler(mock(ResultFactory.class), aspect, new ClientIpResolver("127.0.0.1"));
        handler.handleBindingException(new IllegalArgumentException("REJECTED_SECRET"), request);
        aspect.recordBindingFailure(request);
        assertEquals(0, row().getStatus());
        assertEquals(8, row().getUid());
        assertFalse(row().getRemarks().contains("SECRET"));
        assertTrue(row().getRemarks().contains("参数不合法"));
    }

    @Test
    void bindingAuditSkipsAnonymousReadHandlersAndAlreadyEnteredAspect() throws Throwable {
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(this, getClass().getDeclaredMethod("audited")));
        SecurityContextHolder.clearContext();
        aspect.recordBindingFailure(request);
        verifyNoInteractions(logs);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                JwtUserPrincipal.builder().uid(8).username("operator").build(), null, List.of()));
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(this, getClass().getDeclaredMethod("clear")));
        aspect.recordBindingFailure(request);
        verifyNoInteractions(logs);
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
                new HandlerMethod(this, getClass().getDeclaredMethod("audited")));
        var point = point();
        when(point.proceed()).thenThrow(new IllegalArgumentException("PRIVATE_BODY"));
        assertThrows(IllegalArgumentException.class, () -> aspect.logAroundMethod(point, action));
        aspect.recordBindingFailure(request);
        verify(logs, times(1)).insert(any());
    }

    @Test
    void sanitizerMasksLegacyCredentialFormatsAndLimitsDatabaseColumns() {
        OperationLog row = new OperationLog();
        row.setUsername("u".repeat(120));
        row.setRemarks("{\"password\":\"PW_SECRET\",\"accessToken\":\"TOKEN_SECRET\",\"otpCode\":\"OTP_SECRET\"} "
                + "-----BEGIN PRIVATE KEY-----KEY_SECRET-----END PRIVATE KEY----- https://example.test?token=URL_SECRET "
                + "Bearer AUTH_SECRET");
        OperationLogSanitizer.sanitize(row);
        assertEquals(50, row.getUsername().length());
        for (String secret : List.of("PW_SECRET", "TOKEN_SECRET", "OTP_SECRET", "KEY_SECRET", "URL_SECRET", "AUTH_SECRET")) {
            assertFalse(row.getRemarks().contains(secret), secret);
        }
        assertEquals("id=3", OperationLogSanitizer.summary(Map.of("id", 3, "content", Map.of("id", "BODY"))));
    }

    @Test
    void operationLogAndWhitelistAgreeWithProvidedNginxForwarding() throws Throwable {
        request.setRemoteAddr("127.0.0.1");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "174.137.51.227, 162.158.167.71");
        request.addHeader("X-Real-IP", "162.158.167.71");
        var point = point();
        when(point.proceed()).thenReturn(new Result());
        aspect.logAroundMethod(point, action);
        assertEquals(new ClientIpResolver("127.0.0.1").resolve(request), row().getLoginIp());
        assertEquals("162.158.167.71", row().getLoginIp());
    }

    @Test
    void exceptionLogsUseTheSameTrustedResolver() {
        var clientIps = mock(ClientIpResolver.class);
        when(clientIps.resolve(request)).thenReturn("162.158.167.71");
        var handler = new GlobalExceptionHandler(mock(ResultFactory.class), aspect, clientIps);
        handler.handleBusinessException(new BusinessException("server.param.error"), request);
        verify(clientIps).resolve(request);
    }

    private ProceedingJoinPoint point(Object... args) {
        var point = mock(ProceedingJoinPoint.class);
        when(point.getArgs()).thenReturn(args);
        return point;
    }

    private OperationLog row() {
        var capture = ArgumentCaptor.forClass(OperationLog.class);
        verify(logs).insert(capture.capture());
        return capture.getValue();
    }
}
