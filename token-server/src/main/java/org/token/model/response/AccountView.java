package org.token.model.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号管理页面返回的账号行数据。
 */
@Data
public class AccountView {

    private Integer uid;
    private String username;
    private String avatar;
    private String tag;
    private String locale;
    private String timeZone;
    private Integer state;
    private Integer role;
    private Integer roleId;
    /** 是否已经完成验证器绑定，0 否，1 是。 */
    private Integer totpBound;
    /** 是否强制该账号使用验证器登录，0 否，1 是。 */
    private Integer totpRequired;
    private String name;
    private String remarks;
    private String countryName;
    private String loginIp;
    private LocalDateTime updateTime;
}
