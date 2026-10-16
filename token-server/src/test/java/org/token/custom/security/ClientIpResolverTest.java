package org.token.custom.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    @Test
    void ignoresSpoofedForwardHeaderFromUntrustedRemote() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,10.0.0.0/8");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.5");

        assertEquals("203.0.113.10", resolver.resolve(request));
    }

    @Test
    void resolvesFirstUntrustedAddressBehindTrustedProxyChain() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,10.0.0.0/8");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.5, 10.1.2.3");

        assertEquals("198.51.100.5", resolver.resolve(request));
    }

    @Test
    void rejectsMalformedOrAllTrustedForwardChains() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,10.0.0.0/8");
        HttpServletRequest malformed = mock(HttpServletRequest.class);
        when(malformed.getRemoteAddr()).thenReturn("127.0.0.1");
        when(malformed.getHeader("X-Forwarded-For")).thenReturn("198.51.100.5, not-an-ip");
        assertEquals("", resolver.resolve(malformed));

        HttpServletRequest allTrusted = mock(HttpServletRequest.class);
        when(allTrusted.getRemoteAddr()).thenReturn("127.0.0.1");
        when(allTrusted.getHeader("X-Forwarded-For")).thenReturn("10.1.2.3");
        assertEquals("", resolver.resolve(allTrusted));
    }

    @Test
    void onlyHeaderlessLoopbackIsDirectLocalRecoveryRequest() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
        HttpServletRequest local = mock(HttpServletRequest.class);
        when(local.getRemoteAddr()).thenReturn("127.0.0.1");
        assertTrue(resolver.isDirectLocalRequest(local));

        when(local.getHeader("X-Real-IP")).thenReturn("203.0.113.8");
        assertFalse(resolver.isDirectLocalRequest(local));
    }

    @Test
    void rejectsInvalidTrustedProxyConfiguration() {
        assertThrows(IllegalStateException.class, () -> new ClientIpResolver("proxy.example.com"));
    }
    @Test
    void cloudflareOriginHeadersRequireNginxNormalization() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "174.137.51.227, 162.158.167.71");
        request.addHeader("CF-Connecting-IP", "174.137.51.227");
        // 没有声明Cloudflare可信代理时不能仅凭CF头放行，它可能由浏览器伪造。
        assertEquals("162.158.167.71", resolver.resolve(request));
        // 配套Nginx real_ip仅在直连Cloudflare官方网段时恢复地址，再覆盖转发头。
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "174.137.51.227");
        assertEquals("174.137.51.227", resolver.resolve(request));
    }

    @Test
    void rejectsAmbiguousHeadersAndEmptyForwardedChainEntries() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.2");
        request.addHeader("X-Forwarded-For", "198.51.100.3");
        assertEquals("", resolver.resolve(request));
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "198.51.100.2,");
        assertEquals("", resolver.resolve(request));
    }

    @Test
    void cloudflareHeaderNeverCreatesLocalRecoveryOrTrustFromInternet() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("CF-Connecting-IP", "174.137.51.227");
        request.addHeader("X-Forwarded-For", "174.137.51.227,162.158.167.71");
        assertEquals("203.0.113.7", resolver.resolve(request));
        request.setRemoteAddr("127.0.0.1");
        request.removeHeader("X-Forwarded-For");
        assertFalse(resolver.isDirectLocalRequest(request));
    }
}
