package org.token.model.request.system;

import lombok.Data;

/**
 * 修改后台账号请求。
 */
@Data
public class AccountUpdateRequest {

    private Integer uid;

    private String password;

    private Integer roleId;

    private String remarks;

    private String locale;

    private String avatar;

    private String tag;

    private String timeZone;

    /** null 保留原绑定；空数组明确解绑；不能由前端传入其他账号的身份。 */
    private java.util.List<Long> agentMemberIds;

    public Integer resolveUid() {
        return uid;
    }

    public boolean hasAccountChanges() {
        return (password != null && !password.isBlank())
                || roleId != null
                || locale != null
                || avatar != null
                || tag != null
                || timeZone != null;
    }

    public boolean hasProfileChanges() {
        return remarks != null;
    }
}
