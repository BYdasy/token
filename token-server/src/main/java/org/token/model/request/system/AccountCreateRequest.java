package org.token.model.request.system;

import lombok.Data;

/**
 * 新增后台账号请求。
 */
@Data
public class AccountCreateRequest {

    private String username;

    private String password;

    private Integer roleId;

    private String remarks;

    /**
     * 账号状态，1 正常，0 禁用。
     */
    private Integer state;

    private String avatar;

    private String tag;

    private String locale;

    private String timeZone;

    /** 绑定真实用户邀请链的入口；未提交时不建立绑定，允许多个入口。 */
    private java.util.List<Long> agentMemberIds;
}
