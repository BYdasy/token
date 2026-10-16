package org.token.config;

import jakarta.servlet.http.HttpServletRequest;
import org.token.custom.security.ClientIpResolver;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 恢复可信反代前的同源关系；域名由站点Host决定，真正跨域始终拒绝。 */
final class SameOriginCorsConfigurationSource implements CorsConfigurationSource {
    private final CorsConfigurationSource configured;
    private final ClientIpResolver clientIps;

    SameOriginCorsConfigurationSource(CorsConfigurationSource configured, ClientIpResolver clientIps) {
        this.configured = configured;
        this.clientIps = clientIps;
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        CorsConfiguration base = configured.getCorsConfiguration(request);
        if (base == null || !clientIps.isTrustedProxyRequest(request)) return base;
        String origin = singleHeader(request, "Origin");
        String scheme = singleHeader(request, "X-Forwarded-Proto");
        String host = singleHeader(request, "Host");
        if (origin == null || host == null || !("http".equals(scheme) || "https".equals(scheme))) return base;

        Origin expected = parse(scheme + "://" + host);
        Origin actual = parse(origin);
        if (expected == null || !expected.equals(actual)) return base;

        // 必须复制配置；不能把本次域名追加到共享白名单，从而向其他域名传播授权。
        CorsConfiguration sameOrigin = new CorsConfiguration(base);
        sameOrigin.setAllowedOrigins(List.of(origin));
        return sameOrigin;
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (values == null) return null;
        List<String> headers = Collections.list(values);
        if (headers.size() != 1) return null;
        String value = headers.get(0);
        return value == null || value.isBlank() || !value.equals(value.trim()) || value.indexOf(',') >= 0 ? null : value;
    }

    private static Origin parse(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme(), host = uri.getHost();
            if (scheme == null || host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getPort() == 0 || uri.getPort() > 65535) return null;
            int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(scheme) ? 443 : 80) : uri.getPort();
            return new Origin(scheme.toLowerCase(Locale.ROOT), host.toLowerCase(Locale.ROOT), port);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private record Origin(String scheme, String host, int port) { }
}
