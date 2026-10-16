package org.token.model.request.system;

import lombok.Data;

/**
 * 修改账号状态请求。
 */
@Data
public class AccountStatusRequest {

    private Integer uid;

    /**
     * 账号状态，1 正常，0 禁用。
     */
    private Integer state;

    public Integer resolveUid() {
        return uid;
    }

    public Integer resolveState() {
        return state;
    }
}
