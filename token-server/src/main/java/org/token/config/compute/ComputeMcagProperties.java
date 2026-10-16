package org.token.config.compute;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/** MCAG 商户身份和密钥路径从外部配置加载，私钥不写入数据库或响应。 */
@Data
@ConfigurationProperties(prefix = "token.mcag")
public class ComputeMcagProperties {
    private boolean enabled;
    private boolean pollEnabled = true;
    /** 地址状态复用窗口，单位秒；不是Crypto监听租期。 */
    private int addressCacheSeconds = 60;
    /** 必须显式配置；未接入时不展示一个看似可用的本地网关。 */
    private String baseUrl = "";
    private String merchantId = "";
    private String environment = "LIVE";
    private String credentialId = "";
    private String signingKeyFile = "";
    private String decryptionKeyFile = "";
    private String platformSigningKeyFile = "";
    private String platformEncryptionKeyFile = "";
    /** 只供首次手动同步识别旧通道编码；运行时币网映射统一读取数据库。 */
    private Map<String, Channel> channels = new LinkedHashMap<>();

    @Data
    public static class Channel {
        private String currency;
        private String network;
        private String assetId;
        private String networkId;
        private long mappingVersion;
        private int decimals = 8;
        private boolean active = true;
        private boolean depositEnabled;
        private boolean payoutEnabled;
        private java.math.BigDecimal minimumDeposit = java.math.BigDecimal.ZERO;
        private java.math.BigDecimal minimumPayout = java.math.BigDecimal.ZERO;
    }
}
