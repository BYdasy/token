package org.token.model.security;

/** 用户端独立身份，不携带管理端角色或权限。 */
public record ComputeMemberPrincipal(long id, String username) { }
