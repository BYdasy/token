package org.token.service.compute;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.*;
import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;

/** 双向嵌套 JOSE；不接受重定向、算法协商或未绑定当前请求的响应。 */
@Component
@ConditionalOnProperty(name = "token.mcag.enabled", havingValue = "true")
public class ComputeMcagClient {
    private final ComputeMcagProperties config;
    private final RSAKey signing, decryption, platformSigning, platformEncryption;
    private final HttpClient http;
    private final URI base;
    private final java.util.function.LongSupplier ticker;
    private final java.util.concurrent.atomic.AtomicReference<Cooldown> cooldown = new java.util.concurrent.atomic.AtomicReference<>();
    private record Cooldown(long started, long nanos) { }
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeMcagClient(ComputeMcagProperties config) {
        this(config, System::nanoTime);
    }
    ComputeMcagClient(ComputeMcagProperties config, java.util.function.LongSupplier ticker) {
        this.ticker = ticker;
        this.config = config;
        base = configuredEndpoint(config);
        signing = key(config.getSigningKeyFile(), true, "signing-key-file");
        decryption = key(config.getDecryptionKeyFile(), true, "decryption-key-file");
        platformSigning = key(config.getPlatformSigningKeyFile(), false, "platform-signing-key-file");
        platformEncryption = key(config.getPlatformEncryptionKeyFile(), false, "platform-encryption-key-file");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private static URI configuredEndpoint(ComputeMcagProperties config) {
        URI endpoint;
        try {
            endpoint = URI.create(Objects.toString(config.getBaseUrl(), ""));
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(Objects.toString(endpoint.getHost(), "").toLowerCase(Locale.ROOT));
            if (!("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()) && loopback)
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || !Set.of("", "/").contains(endpoint.getPath()))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalidEndpoint) {
            throw configurationError("base-url", "须显式填写 HTTPS 网关根地址（仅本机联调允许 HTTP），不能包含账号、密码、查询参数或片段");
        }
        if (!"LIVE".equals(config.getEnvironment())) throw configurationError("environment", "必须为 LIVE");
        if (config.getMerchantId() == null || config.getMerchantId().isBlank()) throw configurationError("merchant-id", "不能为空");
        if (config.getCredentialId() == null || config.getCredentialId().isBlank()) throw configurationError("credential-id", "不能为空，须使用平台商户凭证 ID");
        return endpoint;
    }
    private static RSAKey key(String path, boolean privateKey, String property) {
        try {
            if (path == null || path.isBlank()) throw new IllegalArgumentException();
            RSAKey key = RSAKey.parse(Files.readString(Path.of(path)));
            if (key.isPrivate() != privateKey || key.size() < 2048 || key.getKeyID() == null || key.getKeyID().isBlank()) throw new IllegalArgumentException();
            return key;
        } catch (Exception invalidKey) {
            throw configurationError(property, "须为可读取且用途匹配的 RSA JWK 文件（至少 2048 位并包含 kid）；请核对文件和运行账号读取权限");
        }
    }
    private static IllegalStateException configurationError(String property, String detail) {
        // 只输出字段名及修复方向；原异常可能包含 URL 口令、文件路径或密钥内容。
        return new IllegalStateException("token.mcag." + property + " 配置无效：" + detail);
    }
    public Map<String, Object> request(String operation, Map<String, Object> body) {
        requireReady();
        long startedNanos=System.nanoTime();
        String path = "/v1/" + operation, jti = UUID.randomUUID().toString();
        String phase = "REQUEST_JOSE";
        var responseStatus=new java.util.concurrent.atomic.AtomicInteger();
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(config.getMerchantId()).audience("mcag-api")
                    .claim("merchant_id", config.getMerchantId()).claim("environment", config.getEnvironment())
                    .claim("credential_id", config.getCredentialId()).claim("kid", signing.getKeyID())
                    .jwtID(jti).issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(120)))
                    .claim("message_type", "REQUEST").claim("http_path", path).claim("body", body).build();
            SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signing.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
            signed.sign(new RSASSASigner(signing));
            JWEObject envelope = new JWEObject(new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                    .keyID(platformEncryption.getKeyID()).contentType("JWT").build(), new Payload(signed.serialize()));
            envelope.encrypt(new RSAEncrypter(platformEncryption));
            String compact = envelope.serialize();
            if (compact.length() > 131072) throw new BusinessException(400, "server.compute.request.invalid");
            HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/jose").header("Accept", "application/jose")
                    .POST(HttpRequest.BodyPublishers.ofString(compact, StandardCharsets.US_ASCII)).build();
            phase = "HTTP_TRANSPORT";
            // 冷却在响应头到达时生效；429 正文无需信任、读取或等待。
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, info -> {
                // 完整正文失败时仍保留已经收到的状态；状态本身不是可信的订单终态。
                responseStatus.set(info.statusCode());
                if (info.statusCode() == 429) {
                    int seconds = retryAfterSeconds(info.headers().firstValue("Retry-After").orElse(""), Instant.now());
                    long now = ticker.getAsLong(), duration = TimeUnit.SECONDS.toNanos(seconds);
                    cooldown.updateAndGet(current -> current != null && current.nanos() - (now - current.started()) > duration
                            ? current : new Cooldown(now, duration));
                    return new CancelledBody();
                }
                return new BoundedBody();
            });
            HttpResponse<byte[]> response;
            try { response = pending.get(15, TimeUnit.SECONDS); }
            catch (TimeoutException exception) {
                pending.cancel(true);
                throw requestFailure(new BusinessException(504,"server.compute.payment.timeout"),phase,responseStatus.get(),exception,startedNanos);
            }
            catch (InterruptedException exception) { pending.cancel(true); throw exception; }
            phase = "RESPONSE_HEADERS";
            // HTTP 429 仅用于传输冷却；不读取未签名正文，不将其当作订单拒绝或本金释放依据。
            if (response.statusCode() == 429) {
                int seconds = retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(""), Instant.now());

                throw rateLimited(seconds);
            }
            byte[] bytes = response.body();
            MediaType contentType = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElse("application/octet-stream"));
            if (bytes.length > 131072)
                throw new BusinessException(502, "server.compute.payment.invalidResponse");
            if (!"application".equalsIgnoreCase(contentType.getType()) || !"jose".equalsIgnoreCase(contentType.getSubtype())) {
                // Crypto 在认证过滤器拒绝时返回普通 JSON；只诊断 HTTP 接入状态，不读取或信任未签名正文。
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw new BusinessException(502, Map.of("gatewayHttpStatus", response.statusCode()),
                            "server.compute.payment.accessDenied");
                if (response.statusCode() >= 500)
                    throw new BusinessException(502, "server.compute.payment.unavailable");
                throw new BusinessException(502, "server.compute.payment.invalidResponse");
            }
            phase = "RESPONSE_JOSE";
            Map<String, Object> result = verify(new String(bytes, StandardCharsets.US_ASCII), "RESPONSE", path, jti);
            phase = "RESPONSE_BODY";
            if (result.get("error") instanceof Map<?, ?> error) {
                String code = Objects.toString(error.get("code"), "");
                if (!code.matches("[A-Z0-9_]{1,80}")) throw new BusinessException(502, "server.compute.payment.invalidResponse");
                throw new ComputeMcagRejectedException(code);
            }
            if (response.statusCode() != 200 || result.containsKey("error")) throw new BusinessException(502, "server.compute.payment.rejected");
            return result;
        } catch (BusinessException exception) {
            // 可信业务拒绝仍保留专属类型；未验签 HTTP 错误绝不能成为释放提款冻结的依据。
            if (exception instanceof ComputeMcagRejectedException || exception instanceof ComputeMcagRequestException) throw exception;
            throw requestFailure(exception,phase,responseStatus.get(),null,startedNanos);
        }
        catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof HttpTimeoutException)
                throw requestFailure(new BusinessException(504,"server.compute.payment.timeout"),phase,responseStatus.get(),cause,startedNanos);
            // 连接失败、TLS 和连接中断属于传输故障，不能误报成订单内容或签名不匹配。
            String key = cause instanceof java.io.IOException ? "server.compute.payment.unavailable" : "server.compute.payment.invalidResponse";
            throw requestFailure(new BusinessException(502,key),phase,responseStatus.get(),cause,startedNanos);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw requestFailure(new BusinessException(503,"server.compute.payment.unavailable"),phase,responseStatus.get(),exception,startedNanos);
        }
        catch (Exception exception) {
            throw requestFailure(new BusinessException(502,"server.compute.payment.invalidResponse"),phase,responseStatus.get(),
                    "HTTP_TRANSPORT".equals(phase)?exception:null,startedNanos);
        }
    }
    private static ComputeMcagRequestException requestFailure(BusinessException failure,String phase,int httpStatus,
            Throwable transportCause,long startedNanos) {
        String observedPhase="HTTP_TRANSPORT".equals(phase)&&httpStatus>0?"RESPONSE_BODY":phase;
        return new ComputeMcagRequestException(failure,observedPhase,httpStatus,
                ComputeMcagRequestException.classify(transportCause,httpStatus>0),
                Math.max(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startedNanos)));
    }
    /** 冷却期间立即失败，不占线程睡眠，也不自动重发创建订单等写请求。 */
    private void requireReady() {
        for (;;) {
            Cooldown value = cooldown.get();
            if (value == null) return;
            long remaining = value.nanos() - (ticker.getAsLong() - value.started());
            if (remaining > 0) throw rateLimited((int) Math.min(60, (remaining + 999_999_999L) / 1_000_000_000L));
            if (cooldown.compareAndSet(value, null)) return;
            // 其他请求已建立新的冷却，必须重读，不放行旧观察值。
        }
    }
    static int retryAfterSeconds(String header, Instant now) {
        try {
            long seconds = header.matches("[0-9]{1,10}") ? Long.parseLong(header)
                    : Duration.between(now, ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
            return (int) Math.max(1, Math.min(60, seconds));
        } catch (RuntimeException invalid) { return 5; }
    }
    private static BusinessException rateLimited(int seconds) {
        return new BusinessException(503, Map.of("gatewayHttpStatus", 429, "retryAfterSeconds", seconds), "server.compute.payment.rateLimited");
    }
    /** 429 只消费响应头；取消正文订阅，避免限流页面体积或卡顿拖住当前请求。 */
    private static final class CancelledBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = CompletableFuture.completedFuture(new byte[0]);
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
        @Override public void onNext(List<ByteBuffer> ignored) { }
        @Override public void onError(Throwable ignored) { }
        @Override public void onComplete() { }
    }
    /** 同时限制接收体积和完整请求时长，收到响应头后卡住正文也能取消。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> values) {
            for (ByteBuffer value : values) {
                if (buffer.size() + value.remaining() > 131072) { subscription.cancel(); body.completeExceptionally(new IllegalArgumentException("MCAG 响应过大")); return; }
                byte[] bytes = new byte[value.remaining()]; value.get(bytes); buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(buffer.toByteArray()); }
    }
    public Map<String, Object> event(byte[] bytes, String path) {
        return verify(new String(bytes, StandardCharsets.US_ASCII), "EVENT", path, null);
    }
    private Map<String, Object> verify(String compact, String type, String path, String reply) {
        try {
            if (compact.length() > 131072) throw new IllegalArgumentException();
            JWEObject encrypted = JWEObject.parse(compact); JWEHeader outer = encrypted.getHeader();
            if (!JWEAlgorithm.RSA_OAEP_256.equals(outer.getAlgorithm()) || !EncryptionMethod.A256GCM.equals(outer.getEncryptionMethod())
                    || !"JWT".equals(outer.getContentType()) || outer.getCompressionAlgorithm() != null || outer.getCriticalParams() != null
                    || !decryption.getKeyID().equals(outer.getKeyID())) throw new IllegalArgumentException();
            encrypted.decrypt(new RSADecrypter(decryption));
            SignedJWT signed = SignedJWT.parse(encrypted.getPayload().toString());
            if (!JWSAlgorithm.RS256.equals(signed.getHeader().getAlgorithm()) || signed.getHeader().getCriticalParams() != null
                    || !platformSigning.getKeyID().equals(signed.getHeader().getKeyID()) || !signed.verify(new RSASSAVerifier(platformSigning))) throw new IllegalArgumentException();
            JWTClaimsSet claims = signed.getJWTClaimsSet(); Instant now = Instant.now();
            if (!"mcag".equals(claims.getIssuer()) || !List.of(config.getMerchantId()).equals(claims.getAudience())
                    || !config.getMerchantId().equals(claims.getStringClaim("merchant_id")) || !config.getEnvironment().equals(claims.getStringClaim("environment"))
                    || !config.getCredentialId().equals(claims.getStringClaim("credential_id")) || !signing.getKeyID().equals(claims.getStringClaim("kid"))
                    || !type.equals(claims.getStringClaim("message_type")) || !path.equals(claims.getStringClaim("http_path"))
                    || claims.getJWTID() == null || !claims.getJWTID().matches("[A-Za-z0-9_-]{16,128}")
                    || claims.getIssueTime() == null || claims.getExpirationTime() == null
                    || !claims.getExpirationTime().toInstant().isAfter(now) || claims.getIssueTime().toInstant().isAfter(now.plusSeconds(30))
                    || !claims.getExpirationTime().after(claims.getIssueTime())
                    || Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()).getSeconds() > 300)
                throw new IllegalArgumentException();
            Map<String, Object> body = claims.getJSONObjectClaim("body");
            if (body == null || !("EVENT".equals(type) ? Objects.equals(body.get("event_id"), claims.getStringClaim("in_reply_to"))
                    : Objects.equals(reply, claims.getStringClaim("in_reply_to")))) throw new IllegalArgumentException();
            return body;
        } catch (Exception exception) { throw new BusinessException(502, "server.compute.payment.invalidResponse"); }
    }
}
