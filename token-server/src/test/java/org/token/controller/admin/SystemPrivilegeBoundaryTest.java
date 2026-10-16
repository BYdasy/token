package org.token.controller.admin;

import org.token.controller.admin.AccountTotpAdminController;
import org.token.controller.admin.SystemController;

import org.token.custom.LogActionCatalog;
import org.token.model.entity.Account;
import org.token.model.entity.Menu;
import org.token.model.entity.Role;
import org.token.model.request.system.*;
import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.token.service.*;
import org.token.service.compute.ComputeManagementService;
import org.token.util.PasswordUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 接口内部的目标身份边界；全 mock，无数据库、密码外呼及通知。 */
class SystemPrivilegeBoundaryTest {
    private final AccountService accounts=mock(AccountService.class);
    private final RoleService roles=mock(RoleService.class);
    private final MenuService menus=mock(MenuService.class);
    private final TotpService totp=mock(TotpService.class);
    private SystemController controller;
    private Role ordinary, privileged;

    @BeforeEach void setup() {
        controller=new SystemController(new org.token.service.admin.AdminAccountManagementService(accounts, mock(AccountProfileService.class), roles, mock(OperationLogService.class), menus, mock(PasswordUtils.class), new LogActionCatalog(), org.mockito.Mockito.mock(org.token.repository.system.RoleRepository.class), mock(ComputeManagementService.class)), mock(ResultFactory.class));
        ordinary=role(2,0);privileged=role(8,1);
        when(roles.getById(2)).thenReturn(ordinary);when(roles.getById(8)).thenReturn(privileged);
        Account normal=new Account();normal.setUid(20);normal.setUsername("ordinary");normal.setRole(2);when(accounts.findByUid(20)).thenReturn(normal);
        Account admin=new Account();admin.setUid(80);admin.setUsername("another-super");admin.setRole(8);when(accounts.findByUid(80)).thenReturn(admin);
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    private static Role role(int id,int isSuper){Role r=new Role();r.setId(id);r.setName("role-"+id);r.setIsSuper(isSuper);return r;}
    private void login(String... codes){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("operator",null,AuthorityUtils.createAuthorityList(codes)));}

    @Test void ordinaryRoleEditorCannotCreateOrPromoteSuperRole() {
        login("60301","60302");RoleSaveRequest create=new RoleSaveRequest();create.setName("elevated");create.setIsSuper(1);
        assertThrows(AccessDeniedException.class,()->controller.roleCreate(create));
        create.setId(2);assertThrows(AccessDeniedException.class,()->controller.roleUpdate(create));
        verify(roles,never()).createRole(anyString(),any(),anyInt());verify(roles,never()).updateRole(any());
    }
    @Test void ordinaryRoleEditorCannotChangeExistingSuperButCanEditOrdinaryRole() {
        login("60302");RoleSaveRequest request=new RoleSaveRequest();request.setId(8);request.setName("changed");
        assertThrows(AccessDeniedException.class,()->controller.roleUpdate(request));
        request.setId(2);assertDoesNotThrow(()->controller.roleUpdate(request));verify(roles).updateRole(argThat(r->r.getId()==2));
        login("super");request.setIsSuper(1);assertDoesNotThrow(()->controller.roleUpdate(request));verify(roles).updateRole(argThat(r->Integer.valueOf(1).equals(r.getIsSuper())));
    }
    @Test void ordinaryAccountEditorCannotAssignSuperRoleOrEditSuperAccount() {
        login("60201","60202");AccountCreateRequest create=new AccountCreateRequest();create.setUsername("new-admin");create.setPassword("123456");create.setRoleId(8);
        assertThrows(AccessDeniedException.class,()->controller.accountCreate(create));
        AccountUpdateRequest update=new AccountUpdateRequest();update.setUid(20);update.setRoleId(8);
        assertThrows(AccessDeniedException.class,()->controller.accountUpdate(update));
        update.setRoleId(null);update.setUid(80);update.setPassword("123456");assertThrows(AccessDeniedException.class,()->controller.accountUpdate(update));
        verify(accounts,never()).createAccount(any());verify(accounts,never()).updateAccount(any());verify(roles,never()).getRole(anyInt());
    }
    @Test void newRoleGrantRejectsStructuralOrOldParentCodesEvenIfMenuExists() {
        login("603042");Menu parent=new Menu();parent.setId(703);parent.setPid(7);Menu legacy=new Menu();legacy.setId(610);legacy.setPid(6);Menu leaf=new Menu();leaf.setId(70300);leaf.setPid(703);
        when(menus.getMenu(null)).thenReturn(List.of(parent,legacy,leaf));RolePermissionUpdateRequest request=new RolePermissionUpdateRequest();request.setRoleId(2);
        for(int invalid:List.of(703,610)){request.setNewPlatformMenuIds(List.of(invalid));assertThrows(BusinessException.class,()->controller.rolePermissionUpdate(request));}
        request.setNewPlatformMenuIds(List.of(70300));assertDoesNotThrow(()->controller.rolePermissionUpdate(request));verify(roles).updateRole(argThat(r->"70300".equals(r.getRole())));
    }
    @Test void superRoleGrantAndSuperTotpCannotBeChangedByOrdinaryOperators() {
        login("603042","602042","602043");RolePermissionUpdateRequest permission=new RolePermissionUpdateRequest();permission.setRoleId(8);permission.setNewPlatformMenuIds(List.of());
        assertThrows(AccessDeniedException.class,()->controller.rolePermissionUpdate(permission));
        AccountTotpAdminController target=new AccountTotpAdminController(new org.token.service.admin.AdminTotpManagementService(totp,accounts,roles),mock(ResultFactory.class));
        AccountUidRequest reset=new AccountUidRequest();reset.setUid(80);assertThrows(AccessDeniedException.class,()->target.reset(reset));
        AccountTotpRequiredRequest required=new AccountTotpRequiredRequest();required.setUid(80);required.setRequired(false);assertThrows(AccessDeniedException.class,()->target.require(required));
        login("super");assertDoesNotThrow(()->target.reset(reset));verify(totp).reset(80);
    }
}
