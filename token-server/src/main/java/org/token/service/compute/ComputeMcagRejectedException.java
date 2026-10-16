package org.token.service.compute;

import org.token.exception.BusinessException;

/** 仅保存已经验签的网关错误码，供幂等恢复判断；第三方正文不进入用户响应。 */
public final class ComputeMcagRejectedException extends BusinessException {
    private final String gatewayCode;

    ComputeMcagRejectedException(String gatewayCode) {
        super(502, "server.compute.payment.rejected");
        this.gatewayCode = gatewayCode;
    }

    public String getGatewayCode() { return gatewayCode; }
}
