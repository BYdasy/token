package org.token.service.compute;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;
import static org.token.service.compute.ComputeMcagRequestException.TransportFailure.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅用异常类型验证诊断，无 DNS 查询、网络外呼或第三方凭据。 */
class ComputeMcagRequestExceptionTest {
    @Test void dnsAndTlsRemainSpecificInsideTransportWrappers() {
        var connection=new ConnectException("private-host private-token");
        connection.initCause(new UnresolvedAddressException());
        assertEquals(DNS,ComputeMcagRequestException.classify(new ExecutionException(connection),false));
        assertEquals(DNS,ComputeMcagRequestException.classify(new UnknownHostException("private-host"),false));
        assertEquals(TLS,ComputeMcagRequestException.classify(
                new CompletionException(new SSLHandshakeException("private-certificate")),false));
        assertEquals(CONNECT_FAILED,ComputeMcagRequestException.classify(new ConnectException("private-address"),false));
    }

    @Test void timeoutUsesExceptionTypeAndWhetherHeadersWereReceived() {
        assertEquals(CONNECT_TIMEOUT,ComputeMcagRequestException.classify(new HttpConnectTimeoutException("private-host"),false));
        for(Throwable failure:new Throwable[]{new HttpTimeoutException("private"),new SocketTimeoutException("private"),new TimeoutException("private")}) {
            assertEquals(REQUEST_TIMEOUT,ComputeMcagRequestException.classify(new ExecutionException(failure),false));
            assertEquals(BODY_TIMEOUT,ComputeMcagRequestException.classify(new ExecutionException(failure),true));
        }
        assertEquals(INTERRUPTED,ComputeMcagRequestException.classify(new InterruptedException("private"),true));
    }

    @Test void unknownOrCyclicCausesAreBoundedAndNeverRequireMessageInspection() {
        assertEquals(NONE,ComputeMcagRequestException.classify(null,false));
        assertEquals(IO_FAILURE,ComputeMcagRequestException.classify(new IOException("TLS DNS timeout password=private"),false));
        assertEquals(CONNECTION_CLOSED,ComputeMcagRequestException.classify(new EOFException("private"),true));
        assertEquals(UNKNOWN,ComputeMcagRequestException.classify(new IllegalArgumentException("DNS private"),false));
        var first=new IOException("private-first");var second=new IOException("private-second");
        first.initCause(second);second.initCause(first);
        assertEquals(IO_FAILURE,ComputeMcagRequestException.classify(first,false));
    }

    @Test void diagnosisCarriesOnlyTheEnumAndElapsedTimeWithoutChangingBusinessResponse() {
        var cause=new UnknownHostException("private-host password=private-token");
        var failure=new ComputeMcagRequestException(new BusinessException(502,"server.compute.payment.unavailable"),
                "HTTP_TRANSPORT",0,ComputeMcagRequestException.classify(cause,false),731);
        var diagnostic=ComputeMcagPollGuard.diagnose(failure);
        assertEquals("DNS",diagnostic.transportFailure());assertEquals(731,diagnostic.elapsedMillis());
        assertEquals(0,diagnostic.httpStatus());assertEquals(502,diagnostic.businessCode());
        assertEquals("",failure.getData());assertNull(failure.getCause());
        assertFalse(diagnostic.toString().contains("private"));
    }
}
