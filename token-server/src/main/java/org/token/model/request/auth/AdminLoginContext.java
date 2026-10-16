package org.token.model.request.auth;

/** 已由 HTTP 入口解析的客户端信息；不携带 Servlet 或凭据。 */
public record AdminLoginContext(long startedAt, String referer, String language, String clientOs, String clientIp) {
}
