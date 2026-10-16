package org.token.model.entity;

/** 仅用于认证服务内部的凭据核对，不作为 HTTP 响应。 */
public record AdminSessionCredential(String current, String issued) { }
