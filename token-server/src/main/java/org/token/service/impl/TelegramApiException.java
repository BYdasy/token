package org.token.service.impl;

import org.token.exception.BusinessException;

/** 仅携带标准状态，不能把含机器人 Token 的 HTTP 地址或原始响应写入日志。 */
public class TelegramApiException extends BusinessException {
    private final String status;
    private final long retryAfterSeconds;

    public TelegramApiException(String status, long retryAfterSeconds) {
        super("server.notification.telegram." + switch (status) {
            case "INVALID_TOKEN" -> "invalidToken";
            case "WEBHOOK" -> "webhookActive";
            case "CONFLICT" -> "pollingConflict";
            case "RATE_LIMITED" -> "rateLimited";
            case "NETWORK_ERROR" -> "networkError";
            case "BUSY" -> "pollingBusy";
            default -> "apiError";
        });
        this.status = status;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public String status() { return status; }
    public long retryAfterSeconds() { return retryAfterSeconds; }

    static TelegramApiException fromResponse(int code, long retryAfterSeconds) {
        return switch (code) {
            case 401, 404 -> new TelegramApiException("INVALID_TOKEN", 1800);
            case 409 -> new TelegramApiException("CONFLICT", 300);
            case 429 -> new TelegramApiException("RATE_LIMITED", Math.max(30, retryAfterSeconds));
            default -> new TelegramApiException("API_ERROR", 30);
        };
    }
}
