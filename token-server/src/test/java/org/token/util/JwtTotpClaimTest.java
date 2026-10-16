package org.token.util;

import org.token.tools.security.JWTUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtTotpClaimTest {

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(JWTUtil.class, "jwtSecret", "totp-claim-test-secret");
    }

    @Test
    void carriesVerifiedTimestampAndBindOnlyScope() {
        long verifiedAt = System.currentTimeMillis();
        String fullToken = JWTUtil.sign("admin", "1", "hash", 60_000L, verifiedAt);
        String bindToken = JWTUtil.sign("admin", "1", "hash", 60_000L, null, true);

        assertEquals(verifiedAt, JWTUtil.getTotpVerifiedAt(fullToken));
        assertFalse(JWTUtil.isTotpBindOnly(fullToken));
        assertTrue(JWTUtil.isTotpBindOnly(bindToken));
        assertTrue(JWTUtil.verify(bindToken, "admin", "1", "hash"));
    }
}
