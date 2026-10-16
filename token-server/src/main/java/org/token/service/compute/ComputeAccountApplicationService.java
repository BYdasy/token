package org.token.service.compute;

import org.springframework.stereotype.Service;
import org.token.model.request.compute.ComputeAuthRequests;
import org.token.service.compute.ComputeAccountService;

import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.model.response.OperationOutcome;

/** 用户端注册、三种独立登录方法、找回、个人资料与密码操作。 */
@Service

@RequiredArgsConstructor
public class ComputeAccountApplicationService {
    private final ComputeAccountService accounts;

    private OperationOutcome withAccess(java.util.Map<String,Object> value,String clientIp,boolean registration){
        Object member=value.get("member");
        if(member instanceof java.util.Map<?,?> row && row.get("id") instanceof Number id)accounts.recordAccess(id.longValue(),clientIp,registration);
        return OperationOutcome.success(value);
    }

    public OperationOutcome register(  ComputeAuthRequests.Register request,String clientIp) { return withAccess(accounts.register(request),clientIp,true); }

    public OperationOutcome loginUsername(  ComputeAuthRequests.Login request,String clientIp) { return withAccess(accounts.login("username", request),clientIp,false); }

    public OperationOutcome loginEmail(  ComputeAuthRequests.Login request,String clientIp) { return withAccess(accounts.login("email", request),clientIp,false); }

    public OperationOutcome loginPhone(  ComputeAuthRequests.Login request,String clientIp) { return withAccess(accounts.login("phone", request),clientIp,false); }

    public OperationOutcome forgotPassword(  ComputeAuthRequests.Recovery request) {
        accounts.requestRecovery(request);
        return OperationOutcome.create(0, null, "server.compute.recovery.accepted");
    }

    public OperationOutcome resetPassword(  ComputeAuthRequests.Reset request) { accounts.resetPassword(request); return OperationOutcome.success(); }

    public OperationOutcome me( ComputeMemberPrincipal principal) { return OperationOutcome.success(accounts.member(principal.id())); }

    public OperationOutcome profile( ComputeMemberPrincipal principal,   ComputeAuthRequests.Profile request) {
        throw new org.token.exception.BusinessException("server.compute.v11.profileManaged");
    }

    public OperationOutcome password( ComputeMemberPrincipal principal,   ComputeAuthRequests.Password request) {
        accounts.changePassword(principal.id(), request); return OperationOutcome.success();
    }

    public OperationOutcome logout(String authorization) {
        accounts.logout(authorization.startsWith("Bearer ") ? authorization.substring(7) : null); return OperationOutcome.success();
    }

    public OperationOutcome payPassword( ComputeMemberPrincipal principal,  java.util.Map<String, String> request) {
        accounts.changePayPassword(principal.id(), request); return OperationOutcome.success();
    }
}
