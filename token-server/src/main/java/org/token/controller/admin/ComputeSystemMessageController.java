package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.custom.LoggingAspect;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeSystemMessageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** 系统用户通知使用原翻译模块权限，配置仅对全局运营账号开放。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/system-messages")
@RequiredArgsConstructor
public class ComputeSystemMessageController {
    private final ComputeSystemMessageService messages;
    private final ComputeAgentScope scope;
    private final ResultFactory results;
    @GetMapping
    @PreAuthorize("hasAnyAuthority('6060201','6060203','super')")
    public Result list(){boolean global=scope.isGlobalScope();return results.success(Map.of("canManageGlobal",global,"records",global?messages.templates():List.of()));}
    @PutMapping("/{event}")
    @PreAuthorize("hasAnyAuthority('6060203','super')")
    @LogAction(type="系统用户通知模板修改")
    public Result save(@PathVariable String event,@RequestBody Map<String,Object> input){scope.requireGlobalScope();var saved=messages.save(event,input);LoggingAspect.setLogDetails("修改系统用户通知模板："+event,1);return results.success(saved);}
}
