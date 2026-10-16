package org.token.service.admin;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.token.model.request.system.AccountTotpRequiredRequest;
import org.token.model.request.system.AccountUidRequest;
import org.token.model.response.OperationOutcome;
import org.token.exception.BusinessException;
import org.token.service.AccountService;
import org.token.service.TotpService;

/** 管理员设置账号是否必须使用验证器登录，以及重置绑定。 */
@Service

@RequiredArgsConstructor
public class AdminTotpManagementService {

    private final TotpService totpService;
    private final AccountService accountService;
    private final org.token.service.RoleService roleService;

    public OperationOutcome status( AccountUidRequest request) {
        Integer uid = requireUid(request == null ? null : request.getUid());
        return OperationOutcome.success(totpService.status(uid));
    }

    public OperationOutcome require( AccountTotpRequiredRequest request) {
        Integer uid = requireUid(request == null ? null : request.getUid());
        requireSuperForTarget(uid);
        boolean required = request != null && Boolean.TRUE.equals(request.getRequired());
        totpService.setRequired(uid, required);
        return OperationOutcome.success(totpService.status(uid));
    }

    public OperationOutcome reset( AccountUidRequest request) {
        Integer uid = requireUid(request == null ? null : request.getUid());
        requireSuperForTarget(uid);
        totpService.reset(uid);
        return OperationOutcome.success();
    }

    private void requireSuperForTarget(Integer uid) {
        var account=accountService.findByUid(uid);
        var role=account.getRole()==null?null:roleService.getById(account.getRole());
        if(org.token.util.SystemDataProtection.isDefaultAdmin(account.getUid(),account.getUsername()) || (role!=null&&Integer.valueOf(1).equals(role.getIsSuper()))) {
            var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if(authentication==null||!authentication.isAuthenticated()||authentication.getAuthorities().stream().noneMatch(a->"super".equals(a.getAuthority())))
                throw new org.springframework.security.access.AccessDeniedException("只有超级管理员可以修改超级账号验证器");
        }
    }

    private Integer requireUid(Integer uid) {
        if (uid == null || uid <= 0) {
            throw new BusinessException("server.user.uidRequired");
        }
        if (accountService.findByUid(uid) == null) {
            throw new BusinessException("server.user.notFound");
        }
        return uid;
    }
}
