package org.token.tools;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductionKeyGeneratorTest {

    @Test
    void generatesIndependentKeysWithRequiredEntropy() {
        ProductionKeyGenerator.ProductionKeys keys = ProductionKeyGenerator.generate();
        assertEquals(64, decode(keys.jwtSecret()).length);
        assertEquals(32, decode(keys.totpEncryptionKey()).length);
        assertEquals(32, decode(keys.notificationEncryptionKey()).length);
        assertEquals(3, Set.of(
                keys.jwtSecret(),
                keys.totpEncryptionKey(),
                keys.notificationEncryptionKey()
        ).size());
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
