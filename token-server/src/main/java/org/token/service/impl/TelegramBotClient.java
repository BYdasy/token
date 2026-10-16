package org.token.service.impl;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

/** Telegram Bot API 的最小客户端，统一自动轮询和手动获取更新的网络行为。 */
@Component
public class TelegramBotClient {

    private static final Pattern BOT_TOKEN_PATTERN = Pattern.compile("[0-9]+:[A-Za-z0-9_-]+");
    private static final Set<String> SUPPORTED_ACTIONS = Set.of("getUpdates", "sendMessage", "getMe", "getWebhookInfo");

    private final RestClient restClient;
    private final ConcurrentHashMap<String, PollStatus> states = new ConcurrentHashMap<>();
    private final ObjectMapper errorMapper = new ObjectMapper();

    public record PollStatus(String status, String botUsername, Instant checkedAt, Instant lastSuccessAt,
                             Instant retryAt, int failures) { }

    public TelegramBotClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(15_000);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** 测试可替换传输层，无需真实机器人或外网。 */
    TelegramBotClient(RestClient restClient) { this.restClient = restClient; }

    public PollStatus pollingStatus(String token) {
        return states.getOrDefault(fingerprint(token), new PollStatus("INITIALIZING", null, null, null, null, 0));
    }

    public void resetPolling(String token) { states.remove(fingerprint(token)); }

    /** 聊天 ID 自动回复遇到全局限流或网络失败时，同步暂停后续轮询；不改变普通业务发送。 */
    void pausePolling(String token, TelegramApiException failure) { rememberFailure(token, failure); }

    /** getMe 和 Webhook 检查只在首次、恢复或五分钟后进行，绝不自动删除用户 Webhook。 */
    public PollStatus inspectPolling(String token) {
        String key = fingerprint(token);
        PollStatus previous = pollingStatus(token);
        Instant now = Instant.now();
        if (previous.retryAt() != null && now.isBefore(previous.retryAt())) {
            throw new TelegramApiException(previous.status(), Math.max(1,
                    java.time.Duration.between(now, previous.retryAt()).getSeconds()));
        }
        if ("READY".equals(previous.status()) && previous.checkedAt() != null
                && now.isBefore(previous.checkedAt().plusSeconds(300))) return previous;
        try {
            Map<?, ?> bot = call(() -> restClient.get().uri(apiUri(token, "getMe", null))
                    .retrieve().body(Map.class));
            if (!(bot.get("result") instanceof Map<?, ?> details) || !Boolean.TRUE.equals(details.get("is_bot"))) {
                throw new TelegramApiException("API_ERROR", 30);
            }
            Map<?, ?> webhook = call(() -> restClient.get().uri(apiUri(token, "getWebhookInfo", null))
                    .retrieve().body(Map.class));
            if (!(webhook.get("result") instanceof Map<?, ?> info)) throw new TelegramApiException("API_ERROR", 30);
            if (info.get("url") != null && !String.valueOf(info.get("url")).isBlank()) {
                throw new TelegramApiException("WEBHOOK", 300);
            }
            PollStatus status = new PollStatus("READY", String.valueOf(details.get("username")), now,
                    previous.lastSuccessAt(), null, 0);
            states.put(key, status);
            return status;
        } catch (TelegramApiException exception) {
            rememberFailure(token, exception);
            throw exception;
        }
    }

    public Map<?, ?> getUpdates(String token, long offset) {
        inspectPolling(token);
        try {
            Map<?, ?> result = call(() -> restClient.get()
                .uri(apiUri(token, "getUpdates",
                        "offset=" + Math.max(0, offset) + "&timeout=1"))
                .retrieve()
                .body(Map.class));
            PollStatus previous = pollingStatus(token);
            states.put(fingerprint(token), new PollStatus("READY", previous.botUsername(), previous.checkedAt(),
                    Instant.now(), null, 0));
            return result;
        } catch (TelegramApiException exception) {
            rememberFailure(token, exception);
            throw exception;
        }
    }

    public Map<?, ?> sendMessage(String token, String chatId, String text) {
        return call(() -> restClient.post()
                .uri(apiUri(token, "sendMessage", null))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "chat_id", chatId,
                        "text", text == null ? "" : text,
                        "parse_mode", "HTML"
                ))
                .retrieve()
                .body(Map.class));
    }

    private void rememberFailure(String token, TelegramApiException exception) {
        PollStatus previous = pollingStatus(token);
        int failures = Math.min(16, previous.failures() + 1);
        long delay = exception.retryAfterSeconds();
        if (Set.of("NETWORK_ERROR", "API_ERROR").contains(exception.status())) {
            delay = Math.max(delay, Math.min(900, 5L << failures));
        }
        states.put(fingerprint(token), new PollStatus(exception.status(), previous.botUsername(), Instant.now(),
                previous.lastSuccessAt(), Instant.now().plusSeconds(delay), failures));
    }

    private Map<?, ?> call(Supplier<Map<?, ?>> request) {
        try {
            Map<?, ?> response = request.get();
            requireSuccess(response);
            return response;
        } catch (TelegramApiException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new TelegramApiException("INVALID_TOKEN", 1800);
        } catch (RestClientResponseException exception) {
            Map<?, ?> body = Map.of();
            try { body = errorMapper.readValue(exception.getResponseBodyAsByteArray(), Map.class); }
            catch (Exception ignored) { /* 非 JSON 错误只使用 HTTP 状态，不能输出请求地址。 */ }
            throw TelegramApiException.fromResponse(exception.getStatusCode().value(), retryAfter(body));
        } catch (RestClientException exception) {
            throw new TelegramApiException("NETWORK_ERROR", 10);
        }
    }

    static void requireSuccess(Map<?, ?> response) {
        if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
            int code = response != null && response.get("error_code") instanceof Number number ? number.intValue() : 0;
            throw TelegramApiException.fromResponse(code, retryAfter(response));
        }
    }

    private static long retryAfter(Map<?, ?> response) {
        return response != null && response.get("parameters") instanceof Map<?, ?> parameters
                && parameters.get("retry_after") instanceof Number number ? Math.max(1, number.longValue()) : 30;
    }

    static String fingerprint(String token) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((token == null ? "" : token.trim()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    /**
     * Bot Token 中的冒号是 Telegram API 路径的一部分，不能按表单参数编码。
     * 直接传入 URI 也可以避免 RestClient 把已经编码的百分号再次编码，导致正确 Token 仍返回 404。
     */
    static URI apiUri(String token, String action, String query) {
        String normalizedToken = token == null ? "" : token.trim();
        if (!BOT_TOKEN_PATTERN.matcher(normalizedToken).matches()) {
            throw new IllegalArgumentException("Telegram Bot Token 格式不正确");
        }
        if (!SUPPORTED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("不支持的 Telegram API 操作");
        }
        String url = "https://api.telegram.org/bot" + normalizedToken + "/" + action;
        if (query != null && !query.isBlank()) {
            url += "?" + query;
        }
        return URI.create(url);
    }
}
