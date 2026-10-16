package org.token.model.request.system;

import lombok.Data;

/**
 * 系统管理通用 ID 请求。
 */
@Data
public class SystemIdRequest {

    /**
     * 通用主键。
     */
    private Integer id;

    /**
     * 账号 UID。
     *
     * 账号接口使用 uid，角色和菜单接口使用 id。
     */
    private Integer uid;

    public Integer resolveId() {
        return id != null ? id : uid;
    }
}
