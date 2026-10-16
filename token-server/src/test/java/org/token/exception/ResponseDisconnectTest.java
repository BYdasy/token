package org.token.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.token.custom.LoggingAspect;
import org.token.tools.response.ResultFactory;
import org.token.custom.security.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 断连后不再次写响应，同时保留真正的容器和业务故障。 */
class ResponseDisconnectTest {
    private final ResultFactory results = mock(ResultFactory.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(results,
            mock(LoggingAspect.class), new ClientIpResolver("127.0.0.1"));
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private boolean additive;

    @BeforeEach void captureLogs() {
        additive = logger.isAdditive();
        logger.setAdditive(false);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void restoreLogs() {
        logger.detachAppender(logs);
        logger.setAdditive(additive);
        logs.stop();
    }

    @Test void brokenPipeInBootstrapUsesSpecificHandlerAndNeverWritesErrorJson() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DisconnectedController())
                .setControllerAdvice(handler).build();
        var response = mvc.perform(get("/api/compute/public/bootstrap")).andReturn().getResponse();
        assertEquals("", response.getContentAsString());
        verifyNoInteractions(results);
        assertFalse(logs.list.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)));
    }

    @Test void disconnectedResponseIsNotRewrittenWhetherCommittedOrNot() throws Exception {
        for (boolean committed : new boolean[]{false, true}) {
            var response = new MockHttpServletResponse();
            response.setCommitted(committed);
            handler.handleUnusableResponse(new AsyncRequestNotUsableException("响应写入失败",
                    new IOException("Connection reset by peer")), request(), response);
            assertEquals("", response.getContentAsString());
            assertEquals(committed, response.isCommitted());
        }
        verifyNoInteractions(results);
        assertFalse(logs.list.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)));
    }

    @Test void unknownOutputFailureRetainsErrorAndDoesNotAttemptAnotherWrite() throws Exception {
        var response = new MockHttpServletResponse();
        handler.handleUnusableResponse(new AsyncRequestNotUsableException("响应写入失败",
                new IOException("未知容器故障")), request(), response);
        assertEquals("", response.getContentAsString());
        verifyNoInteractions(results);
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                && event.getThrowableProxy() != null));
    }

    @Test void unusableResponseWithoutDisconnectEvidenceIsNotSilenced() {
        handler.handleUnusableResponse(new AsyncRequestNotUsableException("响应已不可用"), request(),
                new MockHttpServletResponse());
        verifyNoInteractions(results);
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR));
    }

    @Test void committedJsonResponseNeverReceivesSecondErrorBody() {
        var response = new MockHttpServletResponse();
        response.setCommitted(true);
        assertNull(handler.handleException(new IllegalStateException("真实内部故障"), request(), response));
        verifyNoInteractions(results);
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR));
    }

    @Test void backendIoFailureIsStillAnInternalError() {
        var response = handler.handleException(new IOException("后端存储不可用"), request(),
                new MockHttpServletResponse());
        assertEquals(500, response.getStatusCode().value());
        verify(results).failure(500, "server.error.internal");
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR));
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/compute/public/bootstrap");
    }

    @RestController static class DisconnectedController {
        @GetMapping("/api/compute/public/bootstrap")
        Object bootstrap() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to write",
                    new IOException("Broken pipe"));
        }
    }
}
