package org.token.model.response;

import java.util.List;

/** 通知发送页使用的精简接收目标，避免暴露账号和角色的敏感管理字段。 */
public record NotificationTargetsView(
        List<AccountTarget> accounts,
        List<RoleTarget> roles
) {
    public record AccountTarget(Integer uid, String username) {
    }

    public record RoleTarget(Integer id, String name) {
    }
}
