package org.token.model.request.system;

import lombok.Data;

/** 当前账号修改自己的展示时区；空字符串表示恢复系统默认值。 */
@Data
public class AccountTimeZoneRequest {
    private String timeZone;
}
