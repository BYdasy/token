package org.token.service.compute;

import org.junit.jupiter.api.Test;
import org.token.config.compute.ComputeMcagProperties;

import static org.junit.jupiter.api.Assertions.*;

/** 配置错误必须在创建 HTTP 客户端前给出字段级诊断，不读取真实密钥、不发出请求。 */
class ComputeMcagClientConfigurationTest {
    @Test
    void missingEndpointMustNotFallBackToLocalhost() {
        assertInvalid(new ComputeMcagProperties(), "base-url");
    }

    @Test
    void invalidEndpointDoesNotExposeCredentialsInTheException() {
        var properties = validIdentity();
        properties.setBaseUrl("https://account:secret@gateway.example.test?token=private");
        var failure = assertInvalid(properties, "base-url");
        assertFalse(failure.getMessage().contains("secret"));
        assertFalse(failure.getMessage().contains("private"));
    }

    @Test
    void remoteHttpEndpointIsRejected() {
        var properties = validIdentity();
        properties.setBaseUrl("http://gateway.example.test");
        assertInvalid(properties, "base-url");
    }

    @Test
    void invalidEnvironmentAndMissingIdentityIdentifyTheField() {
        var properties = validIdentity();
        properties.setEnvironment("TEST");
        assertInvalid(properties, "environment");
        properties.setEnvironment("LIVE");
        properties.setMerchantId("");
        assertInvalid(properties, "merchant-id");
        properties.setMerchantId("merchant_fixture");
        properties.setCredentialId("");
        assertInvalid(properties, "credential-id");
    }

    @Test
    void missingKeyIdentifiesOnlyTheConfigurationField() {
        var properties = validIdentity();
        properties.setSigningKeyFile("\u0000never-display-private-file");
        var failure = assertInvalid(properties, "signing-key-file");
        assertFalse(failure.getMessage().contains("never-display-private-file"));
    }

    @Test
    void explicitLoopbackRemainsAvailableForIsolatedLocalTesting() {
        for (String endpoint : new String[]{"http://127.0.0.1:8080", "http://localhost:8080", "http://[::1]:8080"}) {
            var properties = validIdentity();
            properties.setBaseUrl(endpoint);
            assertInvalid(properties, "signing-key-file");
        }
    }

    private ComputeMcagProperties validIdentity() {
        var properties = new ComputeMcagProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://gateway.example.test");
        properties.setMerchantId("merchant_fixture");
        properties.setCredentialId("credential_fixture");
        return properties;
    }

    private IllegalStateException assertInvalid(ComputeMcagProperties properties, String field) {
        var failure = assertThrows(IllegalStateException.class, () -> new ComputeMcagClient(properties));
        assertTrue(failure.getMessage().contains("token.mcag." + field));
        assertNull(failure.getCause());
        return failure;
    }
}
