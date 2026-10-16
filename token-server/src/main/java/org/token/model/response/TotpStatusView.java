package org.token.model.response;

/** 当前账号或管理员查看账号时返回的非敏感 TOTP 状态。 */
public record TotpStatusView(boolean bound, boolean required) {
}
