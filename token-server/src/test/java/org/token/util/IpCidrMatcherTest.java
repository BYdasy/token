package org.token.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IpCidrMatcherTest {

    @Test
    void matchesExactIpv4AndCidr() {
        assertTrue(IpCidrMatcher.matches("192.168.1.20", "192.168.1.20"));
        assertFalse(IpCidrMatcher.matches("192.168.1.20", "192.168.1.21"));
        assertTrue(IpCidrMatcher.matches("10.20.0.0/16", "10.20.99.8"));
        assertFalse(IpCidrMatcher.matches("10.20.0.0/16", "10.21.0.1"));
    }

    @Test
    void matchesIpv6CidrAndRejectsHostNames() {
        assertTrue(IpCidrMatcher.matches("2001:db8::/32", "2001:db8:1::1"));
        assertFalse(IpCidrMatcher.matches("2001:db8::/32", "2001:db9::1"));
        assertThrows(IllegalArgumentException.class, () -> IpCidrMatcher.normalizeRule("example.com"));
    }

    @Test
    void normalizesNetworkBits() {
        assertEquals("192.168.1.0/24", IpCidrMatcher.normalizeRule("192.168.1.99/24"));
    }
}
