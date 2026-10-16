package org.token.model.request.system;

import lombok.Data;

import java.util.List;

/**
 * 修改角色权限请求。
 */
@Data
public class RolePermissionUpdateRequest {

    private Integer roleId;

    /**
     * 前端勾选的菜单 / 权限码 ID。
     */
    private List<Integer> newPlatformMenuIds;
}
