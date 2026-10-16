package org.token.service.compute;

import org.token.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 会话、找回凭据与密码策略的公共安全原语。数据库只保存随机凭据摘要。 */
public final class ComputeCredentials {
    private static final SecureRandom RANDOM = new SecureRandom();
    private ComputeCredentials() { }

    public static String randomToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境缺少 SHA-256", ex);
        }
    }

    public static void validatePassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 6 || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException("server.compute.v8.passwordRule");
        }
    }

    public static void validatePayPassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 6 || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException("server.compute.password.sixCharacters");
        }
    }
}
