package org.token.model.response.compute;

/** 后台开户结果不包含密码散列、登录 Token 或其他会话凭据。 */
public record ComputeMemberCreated(long id, String username, String email, String phone, int level, boolean enabled,
                                   String inviteCode, Long inviterId, Long groupId, int depth, String createdAt) { }
