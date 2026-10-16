package org.token.model.response;

/** IP 白名单开关与当前请求的实际判定结果。 */
public record IpWhitelistStatusView(
        boolean enabled,
        String clientIp,
        boolean currentClientAllowed,
        long activeRuleCount
) {
}
