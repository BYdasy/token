package org.token.model.security;

import lombok.Builder;
import lombok.Data;
import org.token.model.entity.Account;
import org.springframework.security.core.GrantedAuthority;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 当前登录用户信息
 *
 * 会放到 Spring SecurityContext 中。
 */
@Data
@Builder
public class JwtUserPrincipal implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户 UID
     */
    private Integer uid;

    /**
     * 用户名
     */
    private String username;

    /**
     * 角色 ID
     */
    private Integer role;

    /**
     * 账户状态
     */
    private Integer state;

    /**
     * 头像
     */
    private String avatar;

    /**
     * 标签
     */
    private String tag;

    /**
     * 语言
     */
    private String locale;

    /** 账号独立展示时区；为空时由全局基础设置提供。 */
    private String timeZone;

    /**
     * 权限集合
     */
    private Set<String> authorities;

    /** 当前访问令牌最近通过 TOTP 的时间戳（毫秒）。 */
    private Long totpVerifiedAt;

    /** 仅允许完成 TOTP 绑定的短期令牌。 */
    private boolean totpBindOnly;

    public static JwtUserPrincipal from(Account user, Set<GrantedAuthority> authorities) {
        return JwtUserPrincipal.builder()
                .uid(user.getUid())
                .username(user.getUsername())
                .role(user.getRole())
                .state(user.getState())
                .avatar(user.getAvatar())
                .tag(user.getTag())
                .locale(user.getLocale())
                .timeZone(user.getTimeZone())
                .authorities(
                        authorities == null
                                ? Set.of()
                                : authorities.stream()
                                .map(GrantedAuthority::getAuthority)
                                .collect(Collectors.toSet())
                )
                .build();
    }
}
