package org.token.service.admin;

import org.springframework.stereotype.Service;
import org.token.model.request.auth.AdminLoginContext;
import org.token.model.request.auth.AdminLoginRequest;
import lombok.RequiredArgsConstructor;
import org.token.custom.LoggingAspect;
import org.token.model.entity.Account;
import org.token.model.entity.AccountProfile;
import org.token.model.entity.Role;
import org.token.model.response.OperationOutcome;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.auth.AdminLoginAttemptService;
import org.token.service.AccountProfileService;
import org.token.service.AccountService;
import org.token.service.RoleService;
import org.token.service.TotpService;
import org.token.model.response.TotpStatusView;
import org.token.tools.geo.IpUtils;
import org.token.tools.media.SundryUtils;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台认证接口
 *
 * 登录成功后签发 JWT，密码使用 BCrypt 校验；已登录接口通过
 * Spring Security 的  获取当前用户。
 */
@Service

@RequiredArgsConstructor
public class AdminAuthenticationService {

    private final AccountProfileService accountProfileService;

    private final AccountService accountService;

    private final AdminLoginAttemptService loginAttempts;

    private final TotpService totpService;

    private final RoleService roleService;
    private final org.token.service.auth.AdminLoginSessionService loginSessions;

    /**
     * 用户登录
     */

    public OperationOutcome login(
              AdminLoginRequest loginRequest,
            AdminLoginContext context
    ) {
        long authenticationStartedAt = context.startedAt();
        String username = loginRequest.getUsername().trim();
        LoggingAspect.setAuthLogDetails("后台登录验证", 0, username);
        String password = loginRequest.getPassword();

        Account user = accountService.findByUsername(username);
        if (user == null) {
            writeAuthLog(username, "账户不存在", 0);
            return OperationOutcome.failure(-1, "server.login.error");
        }

        if (user.getState() != null && user.getState() == 0) {
            writeAuthLog(username, "账户未启用", 0);
            return OperationOutcome.failure(-1, "server.login.error");
        }

        AccountProfile userDetail = accountProfileService.findProfile(user.getUid(), username);
        if (userDetail == null) {
            writeAuthLog(username, "账户详情不存在", 0);
            return OperationOutcome.failure(-1, "server.login.error");
        }

        AdminLoginAttemptService.Check passwordCheck = loginAttempts.verifyPassword(user, password);
        if (passwordCheck == AdminLoginAttemptService.Check.LOCKED) {
            writeAuthLog(username, "登录验证连续失败，账号暂时锁定15分钟", 0);
            return OperationOutcome.failure(-1, "server.auth.login.temporarilyLocked");
        }
        if (passwordCheck != AdminLoginAttemptService.Check.ACCEPTED) {
            writeAuthLog(username, "账户密码错误或凭据已变更", 0);
            return OperationOutcome.failure(-1, "server.login.error");
        }

        TotpStatusView totpStatus = totpService.status(user.getUid());
        Long verifiedAt = null;
        if (totpStatus.required()) {
            if (!totpStatus.bound()) {
                String bindToken = loginSessions.issueBinding(user, authenticationStartedAt);
                return OperationOutcome.create(
                        4601,
                        Map.of(
                                "totpRequired", true,
                                "bindRequired", true,
                                "accessToken", bindToken
                        ),
                        "server.auth.totp.bindRequired"
                );
            }
            if (!StringUtils.hasText(loginRequest.getOtpCode())) {
                return OperationOutcome.create(
                        4602,
                        Map.of("totpRequired", true, "bindRequired", false),
                        "server.auth.totp.codeRequired"
                );
            }
            if (!totpService.verify(user.getUid(), loginRequest.getOtpCode(), true)) {
                if (loginAttempts.secondFactorFailed(user) == AdminLoginAttemptService.Check.LOCKED) {
                    writeAuthLog(username, "登录验证连续失败，账号暂时锁定15分钟", 0);
                    return OperationOutcome.failure(-1, "server.auth.login.temporarilyLocked");
                }
                return OperationOutcome.create(
                        4603,
                        Map.of("totpRequired", true, "bindRequired", false),
                        "server.auth.totp.codeInvalid"
                );
            }
            verifiedAt = System.currentTimeMillis();
        }

        loginAttempts.succeeded(user);
        updateLoginInfo(userDetail, context);
        accountProfileService.updateProfile(username, user.getUid(), userDetail);

        String token = loginSessions.issue(user, verifiedAt, authenticationStartedAt);

        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", token);
        data.put("totpRequired", totpStatus.required());
        data.put("totpBound", totpStatus.bound());

        writeAuthLog(username, "登录成功", 1);

        return OperationOutcome.success(data);
    }

    /**
     * 用户登出
     *
     * token 已经在 JwtAuthenticationFilter 中认证过，
     * 这里直接使用当前登录用户。
     */

    public OperationOutcome logout( JwtUserPrincipal principal, String token) {
        JwtUserPrincipal currentUser = requireCurrentUser(principal);
        loginSessions.revoke(token, currentUser.getUid());

        AccountProfile userDetail = accountProfileService.findProfile(currentUser.getUid(), null);
        if (userDetail == null) {
            throw new AuthenticationCredentialsNotFoundException("Token invalid");
        }

        userDetail.setUpdateTime(SundryUtils.getTime());
        accountProfileService.updateById(userDetail);

        writeAuthLog(userDetail.getUsername(), "登出成功", 1);

        return OperationOutcome.success(null);
    }

    /**
     * 权限码
     */

    public OperationOutcome getCodes( JwtUserPrincipal principal) {
        return OperationOutcome.success(frontendAuthorities(requireCurrentUser(principal)));
    }

    /**
     * 获取当前用户信息
     */

    public OperationOutcome getUserInfo( JwtUserPrincipal principal) {
        JwtUserPrincipal currentUser = requireCurrentUser(principal);

        Map<String, Object> data = new HashMap<>();
        data.put("avatar", currentUser.getAvatar());
        data.put("tag", currentUser.getTag());
        data.put("uid", String.valueOf(currentUser.getUid()));
        data.put("userId", String.valueOf(currentUser.getUid()));
        data.put("username", currentUser.getUsername());
        data.put("roleId", currentUser.getRole());
        Role currentRole = currentUser.getRole() == null
                ? null : roleService.getById(currentUser.getRole());
        data.put("roleName", currentRole == null ? null : currentRole.getName());
        data.put("roles", frontendAuthorities(currentUser));
        data.put("realName", currentUser.getUsername());
        data.put("timeZone", currentUser.getTimeZone());
        TotpStatusView totpStatus = totpService.status(currentUser.getUid());
        data.put("totpBound", totpStatus.bound());
        data.put("totpRequired", totpStatus.required());

        return OperationOutcome.success(data);
    }

    /**
     * 获取当前登录用户。
     */
    private JwtUserPrincipal requireCurrentUser(JwtUserPrincipal principal) {
        if (principal == null || principal.getUid() == null) {
            throw new AuthenticationCredentialsNotFoundException("Token invalid");
        }

        return principal;
    }

    /**
     * 更新登录信息。
     */
    private void updateLoginInfo(AccountProfile userDetail, AdminLoginContext context) {
        String lang = context.language();
        String clientOs = context.clientOs();
        String ip = context.clientIp();
        String countryName = getCountryName(ip, lang);

        userDetail.setClientos(clientOs);
        userDetail.setLoginIp(ip);
        userDetail.setLoginCount(getInt(userDetail.getLoginCount()) + 1);
        userDetail.setUpdateTime(SundryUtils.getTime());
        userDetail.setCountryName(countryName);
    }

    /**
     * 获取国家 / 地址名称。
     */
    private String getCountryName(String ip, String lang) {
        try {
            return IpUtils.getIpAddr(ip, lang).getCountry();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 返回前端路由和按钮使用的权限码。
     *
     * JWT 过滤器已经从数据库加载了最新权限，这里直接复用，避免再次查询用户和角色。
     * ROLE_xxx 是 Spring Security 内部角色标识，不参与前端权限判断。
     */
    private List<String> frontendAuthorities(JwtUserPrincipal principal) {
        if (principal.getAuthorities() == null || principal.getAuthorities().isEmpty()) {
            return List.of();
        }

        return principal.getAuthorities().stream()
                .filter(StringUtils::hasText)
                .filter(authority -> !authority.startsWith("ROLE_"))
                .sorted()
                .toList();
    }

    /**
     * Integer 空值转 0。
     */
    private int getInt(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 写认证日志。
     *
     * 注意：
     * 不记录用户密码。
     */
    private void writeAuthLog(String username, String message, int status) {
        String finalUsername = StringUtils.hasText(username) ? username : "";
        String remarks = "用户名: " + finalUsername + " " + message;
        LoggingAspect.setAuthLogDetails(remarks, status, finalUsername);
    }

}
