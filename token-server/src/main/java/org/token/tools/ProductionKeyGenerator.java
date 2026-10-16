package org.token.tools;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 一次性生产密钥生成器，不加载 Spring，也不读取或写入任何配置文件。
 *
 * 运行：java -cp target/classes org.token.tools.ProductionKeyGenerator
 */
public final class ProductionKeyGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ProductionKeyGenerator() {
    }

    public static void main(String[] args) {
        ProductionKeys keys = generate();
        System.out.println("请把以下值保存到生产环境的密钥管理服务，不要提交到代码仓库：");
        System.out.println("EXP_JWT_SECRET=" + keys.jwtSecret());
        System.out.println("EXP_TOTP_ENCRYPTION_KEY=" + keys.totpEncryptionKey());
        System.out.println("EXP_NOTIFICATION_ENCRYPTION_KEY=" + keys.notificationEncryptionKey());
    }

    public static ProductionKeys generate() {
        return new ProductionKeys(randomKey(64), randomKey(32), randomKey(32));
    }

    private static String randomKey(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record ProductionKeys(
            String jwtSecret,
            String totpEncryptionKey,
            String notificationEncryptionKey
    ) {
    }
}
