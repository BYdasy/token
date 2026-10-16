package org.token.service.compute;

import org.springframework.stereotype.Service;
import org.token.model.request.compute.McagCancelRequest;
import lombok.RequiredArgsConstructor;
import org.token.model.response.OperationOutcome;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeMcagPaymentGateway;
import org.token.service.compute.ComputeMerchantService;
import org.token.model.security.JwtUserPrincipal;
import org.token.custom.LoggingAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 本人充值同步与全局财务异常查询，沿用原用户会话和数字权限。 */
@Service

@RequiredArgsConstructor
@ConditionalOnProperty(name = "token.mcag.enabled", havingValue = "true")
public class ComputeMcagAdminService {
    private final ComputeMcagPaymentGateway gateway;
    private final ComputeAgentScope scope;

    private final ComputeMerchantService merchant;

    public OperationOutcome status(int page, int pageSize) { scope.requireGlobalScope(); return OperationOutcome.success(gateway.status(page, pageSize)); }

    public OperationOutcome payoutStatus(int page, int pageSize) { scope.requireGlobalScope(); return OperationOutcome.success(gateway.payoutStatus(page, pageSize)); }

    public OperationOutcome cancel( String reference,   McagCancelRequest request,  JwtUserPrincipal administrator) {
        scope.requireGlobalScope();
        merchant.requireSuperTotp(request.code());
        var result = gateway.cancelUnsubmitted(reference, administrator.getUid(), request.remark());
        LoggingAspect.setLogDetails("核对第三方未创建代付后撤销提款：" + reference, 1);
        return OperationOutcome.success(result);
    }

}
