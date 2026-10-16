package org.token.config.compute;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 只启动属性绑定容器，不扫描业务、连接数据库、读取密钥或执行支付请求。 */
class ComputeMcagConfigurationTest {
    @TempDir Path directory;

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ComputeMcagProperties.class)
    static class PropertiesOnlyConfiguration { }

    @Test
    void additionalLocationBindsTheCurrentPrefixAfterConfigDataLoads() throws Exception {
        Path config = yaml("current.yml", """
                token:
                  mcag:
                    enabled: true
                    base-url: https://gateway.example.test
                    merchant-id: merchant_fixture
                    credential-id: credential_fixture
                    poll-enabled: false
                    signing-key-file: /fixture/private/signing.jwk
                    channels:
                      usdt-tron:
                        currency: USDT
                        network: TRC20
                        asset-id: USDT_TRON
                        network-id: TRON_MAINNET
                """);
        try (var context = start(config)) {
            var properties = context.getBean(ComputeMcagProperties.class);
            assertTrue(properties.isEnabled());
            assertFalse(properties.isPollEnabled());
            assertEquals("https://gateway.example.test", properties.getBaseUrl());
            assertEquals("merchant_fixture", properties.getMerchantId());
            assertEquals("credential_fixture", properties.getCredentialId());
            assertEquals("/fixture/private/signing.jwk", properties.getSigningKeyFile());
            assertEquals("USDT_TRON", properties.getChannels().get("usdt-tron").getAssetId());
        }
    }

    @Test
    void additionalLocationWithOldPrefixFailsBeforeCreatingAnyContext() throws Exception {
        Path config = yaml("legacy.yml", """
                credits:
                  mcag:
                    enabled: true
                    base-url: https://account:secret@gateway.example.test
                    signing-key-file: /private/never-display-this.jwk
                """);
        var failure = assertThrows(IllegalStateException.class, () -> start(config));
        assertTrue(failure.getMessage().contains("credits.mcag"));
        assertTrue(failure.getMessage().contains("token.mcag"));
        assertFalse(failure.getMessage().contains("secret"));
        assertFalse(failure.getMessage().contains("never-display-this"));
        assertNull(failure.getCause());
    }

    @Test
    void oldPrefixCannotSilentlyCoexistWithCurrentConfiguration() throws Exception {
        Path config = yaml("mixed.yml", """
                token:
                  mcag:
                    enabled: false
                    base-url: https://gateway.example.test
                credits:
                  mcag:
                    enabled: false
                """);
        assertThrows(IllegalStateException.class, () -> start(config));
    }

    @Test
    void emptyDisabledConfigurationDoesNotInventALocalGateway() throws Exception {
        Path config = yaml("disabled.yml", "token:\n  mcag:\n    enabled: false\n");
        try (var context = start(config)) {
            var properties = context.getBean(ComputeMcagProperties.class);
            assertFalse(properties.isEnabled());
            assertEquals("", properties.getBaseUrl());
        }
    }

    @Test
    void commandLineOverrideStillTakesPrecedenceOverAdditionalFile() throws Exception {
        Path config = yaml("file.yml", "token:\n  mcag:\n    base-url: https://file.example.test\n");
        try (var context = start(config, "--token.mcag.base-url=https://override.example.test")) {
            assertEquals("https://override.example.test", context.getBean(ComputeMcagProperties.class).getBaseUrl());
        }
    }

    @Test
    void legacyEnvironmentVariableIsAlsoRejectedWithoutExposingItsValue() {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("fixture",
                Map.of("CREDITS_MCAG_BASE_URL", "https://never-display-this.example.test")));
        var failure = assertThrows(IllegalStateException.class,
                () -> new ComputeMcagEnvironmentPostProcessor().postProcessEnvironment(environment, null));
        assertFalse(failure.getMessage().contains("never-display-this"));
    }

    private Path yaml(String name, String content) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private ConfigurableApplicationContext start(Path additional, String... overrides) {
        var application = new SpringApplication(PropertiesOnlyConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        // 显式隔离项目 application.yml 和机器额外配置，仅测试给定的临时 YAML。
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.config.location=optional:" + directory.resolve("not-present.yml").toUri(),
                "--spring.config.additional-location=" + additional.toUri()));
        arguments.addAll(List.of(overrides));
        return application.run(arguments.toArray(String[]::new));
    }
}
