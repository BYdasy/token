package org.token.custom.security;

import jakarta.servlet.http.HttpServletRequest;
import org.token.util.IpCidrMatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 仅在直接连接来自可信代理时解析转发头，避免 X-Forwarded-For 伪造。 */
@Component
public class ClientIpResolver {

    private final List<String> trustedProxyRules;

    public ClientIpResolver(@Value("${exp.security.trusted-proxies:127.0.0.1,::1}") String trustedProxies) {
        this.trustedProxyRules = Arrays.stream(trustedProxies == null ? new String[0] : trustedProxies.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(this::normalizeTrustedRule)
                .distinct()
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remote = normalize(request == null ? null : request.getRemoteAddr());
        if (request == null || !isTrustedProxy(remote)) {
            return remote;
        }

        // 同一转发头出现多次时各代理/容器可能选择不同值，拒绝这种歧义。
        if (hasMultipleHeaders(request, "X-Forwarded-For") || hasMultipleHeaders(request, "X-Real-IP")) return "";
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            if (forwarded.length() > 4096) return "";
            String[] entries = forwarded.split(",", -1);
            if (entries.length > 32) return "";
            List<String> chain = new ArrayList<>();
            for (String item : entries) {
                String candidate = normalize(item);
                if (!StringUtils.hasText(candidate)) {
                    // 可信代理传来的链中只要存在非法地址，就拒绝采用整个头，避免跳过恶意片段。
                    return "";
                }
                chain.add(candidate);
            }
            for (int index = chain.size() - 1; index >= 0; index--) {
                String candidate = chain.get(index);
                if (!isTrustedProxy(candidate)) {
                    return candidate;
                }
            }
            // 声称整条链都是代理地址时无法确定真实客户端，按未知地址处理而不是回退到代理本身。
            return "";
        }

        String realIpHeader = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIpHeader)) {
            return normalize(realIpHeader);
        }
        return remote;
    }

    public boolean isLoopback(String ip) {
        try {
            return IpCidrMatcher.parseNumericAddress(ip).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    /** 只校验直连代理地址，供同源识别复用；不能用转发头声明的客户端地址建立信任。 */
    public boolean isTrustedProxyRequest(HttpServletRequest request) {
        return request != null && isTrustedProxy(normalize(request.getRemoteAddr()));
    }

    /** 只把没有任何转发头的直接本机连接当作恢复通道。 */
    public boolean isDirectLocalRequest(HttpServletRequest request) {
        if (request == null || !isLoopback(normalize(request.getRemoteAddr()))) {
            return false;
        }
        return !StringUtils.hasText(request.getHeader("X-Forwarded-For"))
                && !StringUtils.hasText(request.getHeader("X-Real-IP"))
                && !StringUtils.hasText(request.getHeader("CF-Connecting-IP"))
                && !StringUtils.hasText(request.getHeader("Forwarded"));
    }

    private boolean hasMultipleHeaders(HttpServletRequest request, String name) {
        var headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements()) return false;
        headers.nextElement();
        return headers.hasMoreElements();
    }

    private boolean isTrustedProxy(String ip) {
        return trustedProxyRules.stream().anyMatch(rule -> IpCidrMatcher.matches(rule, ip));
    }

    private String normalizeTrustedRule(String rule) {
        try {
            return IpCidrMatcher.normalizeRule(rule);
        } catch (Exception exception) {
            throw new IllegalStateException("EXP_TRUSTED_PROXIES 包含无效 IP/CIDR：" + rule, exception);
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value) || "unknown".equalsIgnoreCase(value.trim())) {
            return "";
        }
        try {
            InetAddress address = IpCidrMatcher.parseNumericAddress(value.trim());
            return address.getHostAddress();
        } catch (Exception exception) {
            return "";
        }
    }
}
