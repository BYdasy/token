package org.token.tools.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 使用独立通知配置密钥派生 AES-256-GCM 密钥，加密数据库中的渠道凭据。
 * v1 密文仍使用 JWT 密钥解密，避免升级后破坏已有渠道配置。
 */
@Component
public class SensitiveConfigCipher {

    private static final String V1_PREFIX = "enc:v1:";
    private static final String V2_PREFIX = "enc:v2:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${exp.notification.encryption-key:${exp.jwt.secret}}")
    private String secret;

    @Value("${exp.jwt.secret}")
    private String legacyJwtSecret;

    private SecretKeySpec key;
    private SecretKeySpec legacyKey;

    @PostConstruct
    void init() {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("exp.notification.encryption-key 不能为空");
        }
        try {
            key = deriveKey(secret);
            legacyKey = deriveKey(StringUtils.hasText(legacyJwtSecret) ? legacyJwtSecret : secret);
        } catch (Exception e) {
            throw new IllegalStateException("通知配置加密器初始化失败", e);
        }
    }

    public String encrypt(String plainText) {
        if (!StringUtils.hasText(plainText)) {
            return "";
        }
        try {
            byte[] iv = new byte[12];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return V2_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("通知配置加密失败", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (!StringUtils.hasText(encryptedText)) {
            return "";
        }
        if (!encryptedText.startsWith(V1_PREFIX) && !encryptedText.startsWith(V2_PREFIX)) {
            // 兼容迁移前的明文记录，保存一次后会转为密文。
            return encryptedText;
        }
        try {
            boolean legacy = encryptedText.startsWith(V1_PREFIX);
            String prefix = legacy ? V1_PREFIX : V2_PREFIX;
            byte[] combined = Base64.getDecoder().decode(encryptedText.substring(prefix.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, legacy ? legacyKey : key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "通知配置无法解密，请确认 EXP_NOTIFICATION_ENCRYPTION_KEY 和原 EXP_JWT_SECRET 未改变",
                    e
            );
        }
    }

    private SecretKeySpec deriveKey(String source) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("exp:notification:" + source.trim()).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
