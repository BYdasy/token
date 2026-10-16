package org.token.service.compute;

import org.token.exception.BusinessException;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/** 仅供服务端诊断请求阶段；不携带第三方正文、凭据或异常原文。 */
final class ComputeMcagRequestException extends BusinessException {
    private final String requestPhase;
    private final int httpStatus;
    private final TransportFailure transportFailure;
    private final long elapsedMillis;

    enum TransportFailure {
        NONE, DNS, CONNECT_TIMEOUT, CONNECT_FAILED, TLS, REQUEST_TIMEOUT, BODY_TIMEOUT,
        CONNECTION_CLOSED, IO_FAILURE, INTERRUPTED, UNKNOWN
    }

    ComputeMcagRequestException(BusinessException failure, String requestPhase, int httpStatus) {
        this(failure,requestPhase,httpStatus,TransportFailure.NONE,-1);
    }

    ComputeMcagRequestException(BusinessException failure,String requestPhase,int httpStatus,
            TransportFailure transportFailure,long elapsedMillis) {
        super(failure.getCode(), failure.getData(), failure.getMessage());
        this.requestPhase = requestPhase;
        this.httpStatus = httpStatus;
        this.transportFailure=transportFailure;
        this.elapsedMillis=elapsedMillis;
    }

    String requestPhase() { return requestPhase; }
    int httpStatus() { return httpStatus; }
    TransportFailure transportFailure() { return transportFailure; }
    long elapsedMillis() { return elapsedMillis; }

    /** 仅按异常类型分类；不保存 cause 或其消息，避免主机、证书和请求信息进入日志。 */
    static TransportFailure classify(Throwable failure,boolean headersReceived) {
        if(failure==null)return TransportFailure.NONE;
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        boolean dns=false,connectTimeout=false,tls=false,timeout=false,connect=false,closed=false,io=false,interrupted=false;
        for(Throwable current=failure;current!=null&&seen.size()<16&&seen.add(current);current=current.getCause()) {
            dns|=current instanceof UnknownHostException||current instanceof UnresolvedAddressException;
            connectTimeout|=current instanceof HttpConnectTimeoutException;
            tls|=current instanceof SSLException;
            timeout|=current instanceof HttpTimeoutException||current instanceof SocketTimeoutException||current instanceof TimeoutException;
            connect|=current instanceof ConnectException;
            closed|=current instanceof EOFException;
            io|=current instanceof IOException;
            interrupted|=current instanceof InterruptedException;
        }
        if(interrupted)return TransportFailure.INTERRUPTED;
        if(dns)return TransportFailure.DNS;
        if(connectTimeout)return TransportFailure.CONNECT_TIMEOUT;
        if(tls)return TransportFailure.TLS;
        if(timeout)return headersReceived?TransportFailure.BODY_TIMEOUT:TransportFailure.REQUEST_TIMEOUT;
        if(connect)return TransportFailure.CONNECT_FAILED;
        if(closed)return TransportFailure.CONNECTION_CLOSED;
        return io?TransportFailure.IO_FAILURE:TransportFailure.UNKNOWN;
    }
}
