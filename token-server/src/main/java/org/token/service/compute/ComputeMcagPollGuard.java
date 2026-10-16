package org.token.service.compute;

import org.token.exception.BusinessException;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 只为自动补拉限频，不改变持久游标、资金校验或人工查询。 */
final class ComputeMcagPollGuard {
    private final LongSupplier ticker;
    private long failedAt;
    private long retryNanos;
    private long loggedAt;
    private int failures;
    private String loggedReason = "";

    ComputeMcagPollGuard(LongSupplier ticker) { this.ticker = ticker; }

    synchronized boolean ready() {
        return failures == 0 || ticker.getAsLong() - failedAt >= retryNanos;
    }

    synchronized void succeeded(Logger log, String task) {
        if (failures > 0) log.info("MCAG {}已恢复，之前连续失败 {} 次，继续从原持久位置同步", task, failures);
        failures = 0;
        retryNanos = 0;
        loggedReason = "";
    }

    synchronized void failed(Logger log, String task, String phase, String position, int item, Exception failure) {
        var diagnostic = diagnose(failure);
        failures = Math.min(1_000_000, failures + 1);
        int seconds = Math.min(300, 15 * (1 << Math.min(failures - 1, 5)));
        seconds = Math.max(seconds, diagnostic.retryAfterSeconds());
        failedAt = ticker.getAsLong();
        retryNanos = TimeUnit.SECONDS.toNanos(seconds);
        String reason = phase + ":" + diagnostic.category() + ":" + diagnostic.businessKey()
                + ":" + diagnostic.httpStatus() + ":" + diagnostic.gatewayCode() + ":" + diagnostic.requestPhase()+":"+diagnostic.transportFailure();
        // 首次、原因改变和每五分钟输出；日志只记录允许的键/码及位置指纹，不输出第三方正文或 SQL。
        if (!reason.equals(loggedReason) || failedAt - loggedAt >= TimeUnit.MINUTES.toNanos(5)) {
            log.warn("MCAG {}失败，保留持久位置；阶段={}，位置指纹={}，页内序号={}，分类={}，业务键={}，业务码={}，HTTP={}，可信网关码={}，请求阶段={}，传输原因={}，请求耗时={}毫秒，来源={}，连续失败={}，下次重试至少={}秒",
                    task, phase, fingerprint(position), item, diagnostic.category(), diagnostic.businessKey(),
                    diagnostic.businessCode(), diagnostic.httpStatus(), diagnostic.gatewayCode(), diagnostic.requestPhase(),
                    diagnostic.transportFailure(),diagnostic.elapsedMillis(),diagnostic.source(), failures, seconds);
            loggedAt = failedAt;
            loggedReason = reason;
        }
    }

    record Diagnostic(String category, String businessKey, Integer businessCode, int httpStatus,
                      String gatewayCode,String requestPhase,String transportFailure,long elapsedMillis,String source,int retryAfterSeconds) { }

    static Diagnostic diagnose(Exception failure) {
        String key = "-", gatewayCode = "-", phase = "-";
        Integer code = null;
        int status = 0, retryAfter = 0;
        String transportFailure="-";long elapsedMillis=-1;
        if (failure instanceof BusinessException business) {
            key = business.getMessage() != null && business.getMessage().matches("server\\.[A-Za-z0-9_.]{1,150}")
                    ? business.getMessage() : "BUSINESS_ERROR";
            code = business.getCode();
            if (business.getData() instanceof Map<?, ?> metadata) {
                status = integer(metadata.get("gatewayHttpStatus"), 100, 599);
                retryAfter = integer(metadata.get("retryAfterSeconds"), 1, 300);
            }
        }
        if (failure instanceof ComputeMcagRequestException request) {
            phase = request.requestPhase();
            if (request.httpStatus() > 0) status = request.httpStatus();
            transportFailure=request.transportFailure().name();elapsedMillis=request.elapsedMillis();
        }
        if (failure instanceof ComputeMcagRejectedException rejected
                && rejected.getGatewayCode().matches("[A-Z0-9_]{1,80}")) gatewayCode = rejected.getGatewayCode();
        String category = switch (key) {
            case "server.compute.payment.rateLimited" -> "远端限流";
            case "server.compute.payment.timeout" -> "请求超时";
            case "server.compute.payment.accessDenied" -> "接入认证或权限拒绝";
            case "server.compute.payment.unavailable" -> "网络或远端服务不可用";
            case "server.compute.payment.invalidResponse" -> "协议或订单核验失败";
            case "server.compute.payment.rejected" -> "远端业务拒绝";
            default -> failure instanceof DataAccessException ? "本地数据库异常"
                    : failure instanceof BusinessException ? "本地业务校验失败" : "本地程序异常";
        };
        String source = failure.getClass().getSimpleName();
        for (var frame : failure.getStackTrace()) {
            if (frame.getClassName().startsWith("org.token.")) {
                source += "@" + frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
                        + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                break;
            }
        }
        return new Diagnostic(category,key,code,status,gatewayCode,phase,transportFailure,elapsedMillis,source,retryAfter);
    }

    private static int integer(Object value, int minimum, int maximum) {
        if (!(value instanceof Number number)) return 0;
        long parsed = number.longValue();
        return parsed >= minimum && parsed <= maximum ? (int) parsed : 0;
    }

    static String fingerprint(String position) {
        if (position == null || position.isEmpty()) return "HEAD";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(position.getBytes(StandardCharsets.UTF_8)), 0, 6);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
