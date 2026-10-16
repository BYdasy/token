package org.token.model.request.system;

import lombok.Data;

/**
 * 新增 / 修改角色请求。
 */
@Data
public class RoleSaveRequest {

    private Integer id;

    private String name;

    private String remarks;

    /**
     * 是否超级角色，1 是，0 否。
     */
    private Integer isSuper;
}
