package org.token.tools.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;

/**
 * 签发并校验访问令牌。密码散列值参与签名，
 * 因此修改密码后，已有令牌会立即失效。
 */
@Component
public class JWTUtil {

    private static final String ISSUER = "EXP";
    private static volatile String jwtSecret;

    @Value("${exp.jwt.secret}")
    private String configuredSecret;

    @PostConstruct
    public void init() {
        if (!StringUtils.hasText(configuredSecret)) {
            throw new IllegalStateException("exp.jwt.secret 不能为空");
        }
        jwtSecret = configuredSecret.trim();
    }

    public static String sign(String username, String uid, String passwordHash, Long expireTime) {
        return sign(username, uid, passwordHash, expireTime, null);
    }

    public static String sign(
            String username,
            String uid,
            String passwordHash,
            Long expireTime,
            Long totpVerifiedAt
    ) {
        return sign(username, uid, passwordHash, expireTime, totpVerifiedAt, false);
    }

    public static String sign(
            String username,
            String uid,
            String passwordHash,
            Long expireTime,
            Long totpVerifiedAt,
            boolean totpBindOnly
    ) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(uid)) {
            throw new IllegalArgumentException("username 和 uid 不能为空");
        }
        long lifetime = expireTime == null || expireTime <= 0
                ? 14L * 24 * 60 * 60 * 1000
                : expireTime;
        var builder = JWT.create()
                .withIssuer(ISSUER)
                .withClaim("username", username)
                .withClaim("uid", uid)
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + lifetime));
        if (totpVerifiedAt != null && totpVerifiedAt > 0) {
            builder.withClaim("totpVerifiedAt", totpVerifiedAt);
        }
        if (totpBindOnly) {
            // 绑定登录也必须每次签发不同凭据，避免同秒登录碰撞或复用已退出的短令牌。
            builder.withClaim("totpBindOnly", true).withClaim("sid", java.util.UUID.randomUUID().toString());
        }
        return builder.sign(Algorithm.HMAC256(buildUserSecret(passwordHash)));
    }

    public static boolean verify(String token, String username, String uid, String passwordHash) {
        if (!StringUtils.hasText(token) || !StringUtils.hasText(username) || !StringUtils.hasText(uid)) {
            return false;
        }
        try {
            JWT.require(Algorithm.HMAC256(buildUserSecret(passwordHash)))
                    .withIssuer(ISSUER)
                    .withClaim("username", username)
                    .withClaim("uid", uid)
                    .build()
                    .verify(removeBearer(token));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 空闲会话由数据库校验到期与撤销，固定 exp 会在持续操作时错误强制退出。 */
    public static String signSession(String username, String uid, String passwordHash, String sessionId, Long totpVerifiedAt) {
        var builder = JWT.create().withIssuer(ISSUER).withClaim("username", username).withClaim("uid", uid)
                .withClaim("sid", sessionId).withIssuedAt(new Date());
        if (totpVerifiedAt != null && totpVerifiedAt > 0) builder.withClaim("totpVerifiedAt", totpVerifiedAt);
        return builder.sign(Algorithm.HMAC256(buildUserSecret(passwordHash)));
    }

    public static String getSessionId(String token) { return claim(token, "sid"); }
    public static java.time.Instant getIssuedAt(String token) {
        try { return JWT.decode(removeBearer(token)).getIssuedAtAsInstant(); }
        catch (Exception ignored) { return null; }
    }
    public static java.time.Instant getExpiresAt(String token) {
        try { return JWT.decode(removeBearer(token)).getExpiresAtAsInstant(); }
        catch (Exception ignored) { return null; }
    }

    public static String getUsername(String token) {
        return claim(token, "username");
    }

    public static String getUid(String token) {
        return claim(token, "uid");
    }

    public static Long getTotpVerifiedAt(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            return JWT.decode(removeBearer(token)).getClaim("totpVerifiedAt").asLong();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean isTotpBindOnly(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(JWT.decode(removeBearer(token)).getClaim("totpBindOnly").asBoolean());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String claim(String token, String name) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            DecodedJWT jwt = JWT.decode(removeBearer(token));
            return jwt.getClaim(name).asString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String removeBearer(String token) {
        String value = token == null ? "" : token.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value;
    }

    private static String buildUserSecret(String passwordHash) {
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException("JWTUtil 尚未初始化，必须配置 exp.jwt.secret");
        }
        return StringUtils.hasText(passwordHash) ? jwtSecret + ":" + passwordHash : jwtSecret;
    }
}
