package org.token.service.compute;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.token.custom.LoggingAspect;
import org.token.model.response.OperationOutcome;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeCurrencyService;

import java.util.Map;

/** 币种权限独立声明；资产与网络配置只允许有全局范围的后台账号维护。 */
@Service

@RequiredArgsConstructor
public class ComputeCurrencyManagementService {
    private final ComputeCurrencyService service;
    private final ComputeAgentScope scope;

    public OperationOutcome page( String kind,  Map<String, String> query) {
        scope.requireGlobalScope(); return OperationOutcome.success(service.page(kind, query));
    }

    public OperationOutcome options() {
        var options = new java.util.LinkedHashMap<>(service.options());
        options.put("canManageGlobal", scope.isGlobalAdmin());
        return OperationOutcome.success(options);
    }

    public OperationOutcome create( String kind,  Map<String, Object> request) {
        scope.requireGlobalScope();
        Object result = service.save(kind, null, request);
        LoggingAspect.setLogDetails("新增币种配置，模块：" + kind, 1);
        return OperationOutcome.success(result);
    }

    public OperationOutcome update( String kind,  long id,  Map<String, Object> request) {
        scope.requireGlobalScope();
        Object result = service.save(kind, id, request);
        LoggingAspect.setLogDetails("修改币种配置，模块：" + kind + "，编号：" + id, 1);
        return OperationOutcome.success(result);
    }

}
