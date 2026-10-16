package org.token.config;

import org.token.custom.security.IpWhitelistFilter;
import org.token.custom.security.JwtAccessDeniedHandler;
import org.token.custom.security.JwtAuthenticationEntryPoint;
import org.token.custom.security.JwtAuthenticationFilter;
import org.token.custom.security.TotpEnforcementFilter;
import org.token.custom.security.ClientIpResolver;
import org.token.custom.web.RequestTimeZoneFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SecurityConfigTest {

    @Test
    void usesNoConfiguredOriginsAndKeepsExistingHeadersAndMethods() {
        CorsConfigurationSource source = source();

        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/test"));

        assertTrue(cors.getAllowedOrigins().isEmpty());
        assertEquals(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"), cors.getAllowedMethods());
        assertEquals(List.of("*"), cors.getAllowedHeaders());
        assertEquals(List.of("Authorization"), cors.getExposedHeaders());
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());
    }

    @Test
    void directSameOriginWorksWithoutProxyHeadersOrDomainConfiguration() throws Exception {
        for (String path : List.of("/api/auth/login", "/api/compute/auth/login", "/api/compute/user/dashboard")) {
            var request = new MockHttpServletRequest("POST", path);
            request.setRemoteAddr("198.51.100.20");
            request.setScheme("https");request.setServerName("direct.example");request.setServerPort(8443);
            request.addHeader("Origin", "https://direct.example:8443");
            var response = new MockHttpServletResponse();
            assertTrue(new DefaultCorsProcessor().processRequest(source().getCorsConfiguration(request), request, response));
        }
    }

    @Test
    void adminAndUserEndpointsRejectEveryTrueCrossOriginIncludingLocalDevelopment() throws Exception {
        var source = source();
        for (String path : List.of("/api/auth/login", "/api/system/accounts", "/api/compute/auth/register", "/api/compute/user/dashboard")) {
            for (String origin : List.of("http://localhost:5789", "http://localhost:5578", "https://foreign.example")) {
                var request = proxied(path, "site.example", origin);
                var response = new MockHttpServletResponse();
                assertFalse(new DefaultCorsProcessor().processRequest(source.getCorsConfiguration(request), request, response));
                assertEquals(403, response.getStatus());
            }
        }
    }

    @Test
    void anyNewDomainBehindTrustedHttpsProxyWorksWithoutChangingLists() throws Exception {
        var source = source();
        for (String path : List.of("/api/auth/login", "/api/system/accounts", "/api/compute/auth/register", "/api/compute/user/dashboard")) {
            var request = proxied(path, "new-site.example", "https://new-site.example");
            var cors = source.getCorsConfiguration(request);
            assertEquals("https://new-site.example", cors.checkOrigin(request.getHeader("Origin")));
            var response = new MockHttpServletResponse();
            assertTrue(new DefaultCorsProcessor().processRequest(cors, request, response));
            assertEquals("https://new-site.example", response.getHeader("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void trustedSameOriginPreflightKeepsAuthorizationHeadersAndMethods() throws Exception {
        var request = proxied("/api/compute/user/withdrawals", "new-site.example:8443", "https://new-site.example:8443");
        request.setMethod("OPTIONS");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "authorization,content-type,x-background-request");
        var response = new MockHttpServletResponse();
        assertTrue(new DefaultCorsProcessor().processRequest(source().getCorsConfiguration(request), request, response));
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
        assertTrue(response.getHeader("Access-Control-Allow-Headers").contains("authorization"));
        assertEquals("1800", response.getHeader("Access-Control-Max-Age"));
    }

    @Test
    void otherWebsitesAndForwardedHostSpoofingRemainRejected() throws Exception {
        var source = source();
        for (String origin : List.of("https://foreign.example", "http://site.example", "https://site.example:8443", "null", "https://site.example/", "https://site.example@foreign.example")) {
            var request = proxied("/api/system/accounts", "site.example", origin);
            request.addHeader("X-Forwarded-Host", "foreign.example");
            var cors = source.getCorsConfiguration(request);
            assertNull(cors.checkOrigin(origin));
            var response = new MockHttpServletResponse();
            assertFalse(new DefaultCorsProcessor().processRequest(cors, request, response));
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void directUntrustedPeersCannotAssertProxyProtocol() {
        var request = proxied("/api/system/accounts", "site.example", "https://site.example");
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertNull(source().getCorsConfiguration(request).checkOrigin("https://site.example"));
    }

    @Test
    void sameOriginDoesNotMutateSharedListsOrGrantOtherOriginsAdminAccess() {
        var source = source();
        var first = proxied("/api/test", "first.example", "https://first.example");
        assertEquals("https://first.example", source.getCorsConfiguration(first).checkOrigin("https://first.example"));
        var second = proxied("/api/test", "second.example", "https://first.example");
        assertNull(source.getCorsConfiguration(second).checkOrigin("https://first.example"));
        var foreignUser = proxied("/api/system/accounts", "api.example", "https://user.example");
        assertNull(source.getCorsConfiguration(foreignUser).checkOrigin("https://user.example"));
        foreignUser.setRequestURI("/api/compute/user/dashboard");
        assertNull(source.getCorsConfiguration(foreignUser).checkOrigin("https://user.example"));
    }

    @Test
    void rejectsAmbiguousHeadersAndMatchesPortsAndIpv6Exactly() {
        var source = source();
        var multi = proxied("/api/test", "site.example", "https://site.example");
        multi.addHeader("X-Forwarded-Proto", "https");
        assertNull(source.getCorsConfiguration(multi).checkOrigin("https://site.example"));
        var comma = proxied("/api/test", "site.example", "https://site.example");
        comma.removeHeader("X-Forwarded-Proto");comma.addHeader("X-Forwarded-Proto", "https,http");
        assertNull(source.getCorsConfiguration(comma).checkOrigin("https://site.example"));
        for (String host : List.of("localhost:5579", "[::1]:5579", "site.example:443")) {
            String origin = host.equals("site.example:443") ? "https://site.example" : "https://" + host;
            var request = proxied("/api/test", host, origin);
            assertEquals(origin, source.getCorsConfiguration(request).checkOrigin(origin));
        }
    }

    private CorsConfigurationSource source() {
        return config().corsConfigurationSource(new ClientIpResolver("127.0.0.1,::1"));
    }

    private MockHttpServletRequest proxied(String path, String host, String origin) {
        var request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("127.0.0.1");request.setScheme("http");request.setServerName("backend.internal");request.setServerPort(9091);
        request.addHeader("Host", host);request.addHeader("Origin", origin);request.addHeader("X-Forwarded-Proto", "https");
        return request;
    }

    private SecurityConfig config() {
        return new SecurityConfig(
                mock(JwtAuthenticationFilter.class),
                mock(JwtAuthenticationEntryPoint.class),
                mock(JwtAccessDeniedHandler.class),
                mock(IpWhitelistFilter.class),
                mock(TotpEnforcementFilter.class),
                mock(RequestTimeZoneFilter.class)
        );
    }
}
