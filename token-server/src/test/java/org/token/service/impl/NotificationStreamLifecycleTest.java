package org.token.service.impl;

import org.token.exception.NotificationStreamExceptionHandler;
import org.token.exception.GlobalExceptionHandler;
import org.token.tools.response.ResultFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationStreamLifecycleTest {
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
    private final java.util.Map<ch.qos.logback.classic.Logger, Boolean> previous = new java.util.LinkedHashMap<>();

    @org.junit.jupiter.api.BeforeEach
    void captureExpectedFailures() {
        logs.start();
        for (Class<?> type : java.util.List.of(NotificationStreamExceptionHandler.class, GlobalExceptionHandler.class)) {
            var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(type);
            previous.put(logger, logger.isAdditive()); logger.setAdditive(false); logger.addAppender(logs);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void restoreLoggers() {
        previous.forEach((logger, additive) -> { logger.detachAppender(logs); logger.setAdditive(additive); });
        logs.stop();
    }
    @Test
    void completedStreamTimeoutNeverSerializesAResultIntoTheEventStream() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/notifications/my/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("text/event-stream"); response.flushBuffer();
        assertNull(new NotificationStreamExceptionHandler().handleStreamLifecycle(
                new AsyncRequestTimeoutException(), request, response));
        assertEquals("", response.getContentAsString());
        assertEquals("text/event-stream", response.getContentType());
    }

    @Test
    void genuineFailureBeforeStreamStartsRetainsFailureStatus() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/notifications/my/stream");
        var result = new NotificationStreamExceptionHandler().handleStreamLifecycle(
                new AsyncRequestTimeoutException(), request, new MockHttpServletResponse());
        assertEquals(503, result.getStatusCode().value());
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR));
    }

    @Test
    void unknownCommittedFailureKeepsLogWithoutWritingASecondResponse() {
        ResultFactory results = mock(ResultFactory.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/notifications/my/stream");
        MockHttpServletResponse response = new MockHttpServletResponse(); response.setCommitted(true);
        assertNull(new GlobalExceptionHandler(results, org.mockito.Mockito.mock(org.token.custom.LoggingAspect.class), new org.token.custom.security.ClientIpResolver("127.0.0.1")).handleException(new IllegalStateException("测试内部异常"), request, response));
        verifyNoInteractions(results);
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR && event.getThrowableProxy() != null));
    }
}
