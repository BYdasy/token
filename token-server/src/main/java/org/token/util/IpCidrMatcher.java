package org.token.util;

import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.Locale;

/** 只解析数字 IP，不执行主机名 DNS 查询的精确地址/CIDR 匹配器。 */
public final class IpCidrMatcher {

    private IpCidrMatcher() {
    }

    public static boolean matches(String rule, String address) {
        try {
            ParsedRule parsed = parse(rule);
            byte[] target = parseNumericAddress(address).getAddress();
            if (parsed.network().length != target.length) {
                return false;
            }
            int fullBytes = parsed.prefixLength() / 8;
            int remainingBits = parsed.prefixLength() % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (parsed.network()[index] != target[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xff << (8 - remainingBits)) & 0xff;
            return (parsed.network()[fullBytes] & mask) == (target[fullBytes] & mask);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static String normalizeRule(String rule) {
        ParsedRule parsed = parse(rule);
        try {
            String address = InetAddress.getByAddress(parsed.network()).getHostAddress().toLowerCase(Locale.ROOT);
            int maximum = parsed.network().length * 8;
            return parsed.prefixLength() == maximum ? address : address + "/" + parsed.prefixLength();
        } catch (Exception exception) {
            throw new IllegalArgumentException("IP 地址无效", exception);
        }
    }

    private static ParsedRule parse(String rule) {
        if (!StringUtils.hasText(rule)) {
            throw new IllegalArgumentException("IP 地址不能为空");
        }
        String value = stripBrackets(rule.trim());
        String[] parts = value.split("/", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("CIDR 格式无效");
        }
        InetAddress address = parseNumericAddress(parts[0]);
        byte[] bytes = address.getAddress();
        int maximum = bytes.length * 8;
        int prefix = maximum;
        if (parts.length == 2) {
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("CIDR 前缀无效");
            }
        }
        if (prefix < 0 || prefix > maximum) {
            throw new IllegalArgumentException("CIDR 前缀超出范围");
        }
        byte[] network = bytes.clone();
        for (int bit = prefix; bit < maximum; bit++) {
            int byteIndex = bit / 8;
            int bitIndex = 7 - bit % 8;
            network[byteIndex] = (byte) (network[byteIndex] & ~(1 << bitIndex));
        }
        return new ParsedRule(network, prefix);
    }

    public static InetAddress parseNumericAddress(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("IP 地址不能为空");
        }
        String normalized = stripBrackets(value.trim());
        boolean ipv4 = normalized.matches("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
        boolean ipv6 = normalized.contains(":") && normalized.matches("^[0-9a-fA-F:.%]+$");
        if (!ipv4 && !ipv6) {
            throw new IllegalArgumentException("只允许数字 IP 地址");
        }
        try {
            InetAddress address = InetAddress.getByName(normalized);
            if (ipv4) {
                String[] octets = normalized.split("\\.");
                for (String octet : octets) {
                    if (Integer.parseInt(octet) > 255) {
                        throw new IllegalArgumentException("IPv4 地址无效");
                    }
                }
            }
            return address;
        } catch (Exception exception) {
            throw new IllegalArgumentException("IP 地址无效", exception);
        }
    }

    private static String stripBrackets(String value) {
        if (value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record ParsedRule(byte[] network, int prefixLength) {
    }
}
