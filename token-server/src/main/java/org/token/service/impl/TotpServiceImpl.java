package org.token.service.impl;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.token.model.entity.AccountTotp;
import org.token.model.response.TotpSetupView;
import org.token.model.response.TotpStatusView;
import org.token.exception.BusinessException;
import org.token.service.TotpService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

@Slf4j
@Service
public class TotpServiceImpl implements TotpService {
    private final org.token.repository.system.AccountTotpRepository repository;

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;
    private static final int TOTP_PERIOD_SECONDS = 30;
    private static final int TOTP_DIGITS = 6;
    private static final int ALLOWED_TIME_SKEW = 1;

    private final SecureRandom secureRandom = new SecureRandom();
    private final String issuer;
    private final byte[] encryptionKey;
    private final org.token.service.auth.AdminLoginSessionService loginSessions;

    @org.springframework.beans.factory.annotation.Autowired
    public TotpServiceImpl(
            @Value("${exp.totp.issuer:Tensor AI}") String issuer,
            @Value("${exp.totp.encryption-key}") String encryptionKey,
            org.token.service.auth.AdminLoginSessionService loginSessions,
            org.token.repository.system.AccountTotpRepository repository
    ) {
        this.repository=repository;
        this.issuer = StringUtils.hasText(issuer) ? issuer.trim() : "Tensor AI";
        if (!StringUtils.hasText(encryptionKey)) {
            throw new IllegalStateException("exp.totp.encryption-key 不能为空");
        }
        this.encryptionKey = sha256(encryptionKey.trim().getBytes(StandardCharsets.UTF_8));
        this.loginSessions = loginSessions;
    }

    public TotpServiceImpl(String issuer,String encryptionKey,org.token.service.auth.AdminLoginSessionService loginSessions) {
        this(issuer,encryptionKey,loginSessions,null);
    }

    TotpServiceImpl(String issuer, String encryptionKey) { this(issuer, encryptionKey, null); }

    @Override
    public TotpStatusView status(Integer uid) {
        requireUid(uid);
        AccountTotp record = repository.getById(uid);
        if (record == null) throw new IllegalStateException("管理员安全记录不存在");
        return new TotpStatusView(isBound(record), Integer.valueOf(1).equals(record.getRequired()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TotpSetupView setup(Integer uid, String username) {
        requireUid(uid);
        repository.lockAccount(uid);
        AccountTotp existing = repository.getById(uid);
        if (isBound(existing)) {
            throw new BusinessException("server.auth.totp.alreadyBound");
        }
        byte[] secretBytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secretBytes);
        String secret = base32Encode(secretBytes);

        String ciphertext = encrypt(secret);
        if (existing == null) {
            AccountTotp update = new AccountTotp();
            update.setUid(uid);
            update.setSecretCiphertext(ciphertext);
            update.setEnabled(0);
            update.setRequired(0);
            repository.save(update);
        } else {
            repository.resetSecret(uid,ciphertext);
        }

        String accountName = StringUtils.hasText(username) ? username.trim() : String.valueOf(uid);
        String label = urlEncode(issuer + ":" + accountName);
        String uri = "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + TOTP_DIGITS
                + "&period=" + TOTP_PERIOD_SECONDS;
        return new TotpSetupView(secret, uri, qrCode(uri));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Integer uid, String code) {
        AccountTotp record = requireRecord(uid);
        if (isBound(record)) throw new BusinessException("server.auth.totp.alreadyBound");
        if (!StringUtils.hasText(record.getSecretCiphertext())) {
            throw new BusinessException("server.auth.totp.setupRequired");
        }
        Verification verification = verifyCode(record, code);
        if (!verification.valid()) {
            throw new BusinessException("server.auth.totp.codeInvalid");
        }
        record.setEnabled(1);
        record.setLastUsedCounter(verification.counter());
        record.setConfirmedAt(org.token.tools.media.SundryUtils.getTime());
        record.setUpdatedAt(org.token.tools.media.SundryUtils.getTime());
        repository.updateById(record);
        if (loginSessions != null) loginSessions.revokeAll(uid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean verify(Integer uid, String code, boolean preventReplay) {
        AccountTotp record = requireRecord(uid);
        if (!isBound(record)) {
            return false;
        }
        Verification verification = verifyCode(record, code);
        if (!verification.valid()) {
            return false;
        }
        if (preventReplay) {
            // 条件更新保证并发登录时同一个 30 秒验证码只能成功一次。
            return repository.unusedCounter(uid,verification.counter());
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disable(Integer uid, String code) {
        AccountTotp record = requireRecord(uid);
        if (Integer.valueOf(1).equals(record.getRequired())) {
            throw new BusinessException("server.auth.totp.requiredCannotDisable");
        }
        if (!verify(uid, code, true)) {
            throw new BusinessException("server.auth.totp.codeInvalid");
        }
        reset(uid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setRequired(Integer uid, boolean required) {
        requireUid(uid);
        repository.lockAccount(uid);
        AccountTotp record = repository.getById(uid);
        boolean changed = record == null || required != Integer.valueOf(1).equals(record.getRequired());
        if (record == null) {
            record = new AccountTotp();
            record.setUid(uid);
            record.setEnabled(0);
        }
        record.setRequired(required ? 1 : 0);
        record.setUpdatedAt(org.token.tools.media.SundryUtils.getTime());
        repository.saveOrUpdate(record);
        if (changed && loginSessions != null) loginSessions.revokeAll(uid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reset(Integer uid) {
        requireUid(uid);
        repository.lockAccount(uid);
        AccountTotp record = repository.getById(uid);
        if (loginSessions != null) loginSessions.revokeAll(uid);
        if (record == null) {
            return;
        }
        repository.resetSecret(uid, "");
    }

    private Verification verifyCode(AccountTotp record, String code) {
        if (!StringUtils.hasText(code) || !code.trim().matches("^\\d{6}$")) {
            return Verification.invalid();
        }
        byte[] secret = base32Decode(decrypt(record.getSecretCiphertext()));
        long currentCounter = System.currentTimeMillis() / 1000L / TOTP_PERIOD_SECONDS;
        for (int skew = -ALLOWED_TIME_SKEW; skew <= ALLOWED_TIME_SKEW; skew++) {
            long counter = currentCounter + skew;
            String expected = generateCode(secret, counter);
            if (MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    code.trim().getBytes(StandardCharsets.US_ASCII)
            )) {
                return new Verification(true, counter);
            }
        }
        return Verification.invalid();
    }

    private String generateCode(byte[] secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (Exception exception) {
            throw new IllegalStateException("生成 TOTP 验证码失败", exception);
        }
    }

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception exception) {
            throw new IllegalStateException("加密 TOTP 密钥失败", exception);
        }
    }

    private String decrypt(String ciphertext) {
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[payload.length - iv.length];
            System.arraycopy(payload, 0, iv, 0, iv.length);
            System.arraycopy(payload, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new BusinessException("server.auth.totp.secretInvalid");
        }
    }

    private String qrCode(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 280, 280);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("生成 TOTP 二维码失败", exception);
        }
    }

    private AccountTotp requireRecord(Integer uid) {
        requireUid(uid);
        repository.lockAccount(uid);
        AccountTotp record = repository.getById(uid);
        if (record == null) {
            throw new BusinessException("server.auth.totp.setupRequired");
        }
        return record;
    }

    private void requireUid(Integer uid) {
        if (uid == null || uid <= 0) {
            throw new BusinessException("server.user.uidRequired");
        }
    }

    private boolean isBound(AccountTotp record) {
        return record != null
                && Integer.valueOf(1).equals(record.getEnabled())
                && StringUtils.hasText(record.getSecretCiphertext());
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String base32Encode(byte[] input) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                result.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 31));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            result.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 31));
        }
        return result.toString();
    }

    private byte[] base32Decode(String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char character : value.replace("=", "").toUpperCase(Locale.ROOT).toCharArray()) {
            int index = BASE32_ALPHABET.indexOf(character);
            if (index < 0) {
                throw new BusinessException("server.auth.totp.secretInvalid");
            }
            buffer = (buffer << 5) | index;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return output.toByteArray();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record Verification(boolean valid, long counter) {
        static Verification invalid() {
            return new Verification(false, -1L);
        }
    }
}
