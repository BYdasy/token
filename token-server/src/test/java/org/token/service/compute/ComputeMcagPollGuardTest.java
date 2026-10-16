package org.token.service.compute;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComputeMcagPollGuardTest {
    @Test void consecutiveFailuresBackOffToFiveMinutesWithoutSleepingAndSuccessResets() {
        var clock=new AtomicLong();var guard=new ComputeMcagPollGuard(clock::get);var log=mock(Logger.class);
        var failure=new BusinessException(504,"server.compute.payment.timeout");
        for(int seconds:new int[]{15,30,60,120,240,300,300}) {
            assertTrue(guard.ready());
            guard.failed(log,"逐笔充值补拉","请求","order-secret",0,failure);
            assertFalse(guard.ready());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds)-1);
            assertFalse(guard.ready());
            clock.incrementAndGet();assertTrue(guard.ready());
        }
        guard.succeeded(log,"逐笔充值补拉");
        guard.failed(log,"逐笔充值补拉","请求","order-secret",0,failure);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(15));assertTrue(guard.ready());
    }

    @Test void unchangedFailureLogsOnceUntilFiveMinutesOrReasonChanges() {
        var clock=new AtomicLong();var guard=new ComputeMcagPollGuard(clock::get);var log=mock(Logger.class);
        var failure=new BusinessException(504,"server.compute.payment.timeout");
        guard.failed(log,"逐笔充值补拉","请求","private-order",0,failure);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(15));
        guard.failed(log,"逐笔充值补拉","请求","private-order",0,failure);
        verify(log,times(1)).warn(anyString(),any(Object[].class));
        clock.addAndGet(TimeUnit.MINUTES.toNanos(5));
        guard.failed(log,"逐笔充值补拉","请求","private-order",0,failure);
        verify(log,times(2)).warn(anyString(),any(Object[].class));
        guard.failed(log,"逐笔充值补拉","本地入账","private-order",1,new BusinessException("server.compute.invalidInput"));
        verify(log,times(3)).warn(anyString(),any(Object[].class));
    }

    @Test void transportCooldownIsHonouredAndTasksHaveIndependentBackoff() {
        var clock=new AtomicLong();var guard=new ComputeMcagPollGuard(clock::get);
        guard.failed(mock(Logger.class),"逐笔充值补拉","请求","",0,
                new BusinessException(503,Map.of("gatewayHttpStatus",429,"retryAfterSeconds",60),"server.compute.payment.rateLimited"));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(59));assertFalse(guard.ready());
        assertTrue(new ComputeMcagPollGuard(clock::get).ready());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));assertTrue(guard.ready());
    }

    @Test void diagnosticsRetainOnlySafeKeysCodesAndRequestPhase() {
        var error=new ComputeMcagRequestException(new BusinessException(502,Map.of("gatewayHttpStatus",403,
                "body","private credential"),"server.compute.payment.accessDenied"),"RESPONSE_HEADERS",403);
        var diagnostic=ComputeMcagPollGuard.diagnose(error);
        assertEquals("接入认证或权限拒绝",diagnostic.category());
        assertEquals(403,diagnostic.httpStatus());assertEquals("RESPONSE_HEADERS",diagnostic.requestPhase());
        assertFalse(diagnostic.toString().contains("private credential"));
        assertEquals("PERMISSION_DENIED",ComputeMcagPollGuard.diagnose(new ComputeMcagRejectedException("PERMISSION_DENIED")).gatewayCode());
        assertEquals("BUSINESS_ERROR",ComputeMcagPollGuard.diagnose(new BusinessException("password=private\nsecret")).businessKey());
        assertEquals("-",ComputeMcagPollGuard.diagnose(new ComputeMcagRejectedException("untrusted\ntext")).gatewayCode());
    }

    @Test void databaseFailuresDoNotLeakSqlAndPositionsAreStableFingerprints() {
        var failure=new BadSqlGrammarException("query","SELECT sensitive_customer_record",new SQLException("credential private"));
        var diagnostic=ComputeMcagPollGuard.diagnose(failure);
        assertEquals("本地数据库异常",diagnostic.category());
        assertFalse(diagnostic.toString().contains("sensitive_customer_record"));assertFalse(diagnostic.toString().contains("credential"));
        assertEquals("HEAD",ComputeMcagPollGuard.fingerprint(""));
        String fingerprint=ComputeMcagPollGuard.fingerprint("private-order");
        assertTrue(fingerprint.matches("[0-9a-f]{12}"));
        assertEquals(fingerprint,ComputeMcagPollGuard.fingerprint("private-order"));
        assertNotEquals(fingerprint,ComputeMcagPollGuard.fingerprint("different-order"));
    }

    @Test void transportReasonChangesAreLoggedButElapsedTimeDoesNotCauseLogSpam() {
        var guard=new ComputeMcagPollGuard(new AtomicLong()::get);var log=mock(Logger.class);
        for(long elapsed:new long[]{30,700})guard.failed(log,"支付事件同步","请求","",0,
                new ComputeMcagRequestException(new BusinessException(502,"server.compute.payment.unavailable"),
                        "HTTP_TRANSPORT",0,ComputeMcagRequestException.TransportFailure.DNS,elapsed));
        verify(log,times(1)).warn(anyString(),any(Object[].class));
        guard.failed(log,"支付事件同步","请求","",0,
                new ComputeMcagRequestException(new BusinessException(502,"server.compute.payment.unavailable"),
                        "HTTP_TRANSPORT",0,ComputeMcagRequestException.TransportFailure.TLS,800));
        verify(log,times(2)).warn(anyString(),any(Object[].class));
    }
}
