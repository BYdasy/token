package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.entity.*;
import org.token.model.request.system.*;
import org.token.model.request.QueryParams;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.*;
import org.token.model.security.JwtUserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 账号、角色、权限、菜单和操作日志接口。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {
    private final org.token.service.admin.AdminAccountManagementService service;
    private final ResultFactory resultFactory;

    @PostMapping("/user_role/account/list")
    @PreAuthorize("hasAnyAuthority('60200','super')")
    public Result accountList(@RequestBody(required = false) QueryParams params) {
        var outcome = service.accountList(params);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/account/create")
    @PreAuthorize("hasAnyAuthority('60201','super') and (#request.agentMemberIds == null or hasAnyAuthority('60206','super')) and (#request.state == null or #request.state == 1 or hasAnyAuthority('60205','super'))")
    @LogAction(type = "账户新增")

    public Result accountCreate(@RequestBody AccountCreateRequest request) {
        var outcome = service.accountCreate(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/account/update")
    @PreAuthorize("hasAnyAuthority('60202','super') and (#request.agentMemberIds == null or hasAnyAuthority('60206','super'))")
    @LogAction(type = "账户修改")

    public Result accountUpdate(@RequestBody AccountUpdateRequest request) {
        var outcome = service.accountUpdate(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/account/enable/update")
    @PreAuthorize("hasAnyAuthority('60205','super')")
    @LogAction(type = "账户状态")
    public Result accountEnableUpdate(@RequestBody AccountStatusRequest request) {
        var outcome = service.accountEnableUpdate(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/account/delete")
    @PreAuthorize("hasAnyAuthority('60203','super')")
    @LogAction(type = "账户删除")

    public Result accountDelete(
            @RequestBody SystemIdRequest request,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        var outcome = service.accountDelete(request, principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/account/roleList")
    @PreAuthorize("hasAnyAuthority('60200','60201','60202','60300','super')")
    public Result roleListForAccount() {
        var outcome = service.roleListForAccount();
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/role/list")
    @PreAuthorize("hasAnyAuthority('60300','super')")
    public Result rolePage(@RequestBody(required = false) QueryParams params) {
        var outcome = service.rolePage(params);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/permission/read")
    @PreAuthorize("hasAnyAuthority('603041','super')")
    public Result rolePermissionRead(@RequestBody SystemIdRequest request) {
        var outcome = service.rolePermissionRead(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/permission/update")
    @PreAuthorize("hasAnyAuthority('603042','super')")
    @LogAction(type = "角色权限修改")
    public Result rolePermissionUpdate(@RequestBody RolePermissionUpdateRequest request) {
        var outcome = service.rolePermissionUpdate(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/role/create")
    @PreAuthorize("hasAnyAuthority('60301','super')")
    @LogAction(type = "角色创建")
    public Result roleCreate(@RequestBody RoleSaveRequest request) {
        var outcome = service.roleCreate(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/role/update")
    @PreAuthorize("hasAnyAuthority('60302','super')")
    @LogAction(type = "角色修改")
    public Result roleUpdate(@RequestBody RoleSaveRequest request) {
        var outcome = service.roleUpdate(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/user_role/role/delete")
    @PreAuthorize("hasAnyAuthority('60303','super')")
    @LogAction(type = "角色删除")
    public Result roleDelete(@RequestBody SystemIdRequest request) {
        var outcome = service.roleDelete(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/system_log/read")
    @PreAuthorize("hasAnyAuthority('60400','super')")
    public Result systemLogRead(@RequestBody(required = false) QueryParams params) {
        var outcome = service.systemLogRead(params);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/system_log/actions")
    @PreAuthorize("hasAnyAuthority('60400','super')")
    public Result systemLogActions(@RequestBody(required = false) ActionQueryRequest request) {
        var outcome = service.systemLogActions(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/menu/tree")
    @PreAuthorize("hasAnyAuthority('603041','super')")
    public Result menuTree() {
        var outcome = service.menuTree();
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
