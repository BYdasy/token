package org.token.controller.admin;

import org.token.controller.admin.SystemController;

import org.token.custom.LogActionCatalog;
import org.token.model.request.system.AccountCreateRequest;
import org.token.model.request.system.AccountUpdateRequest;
import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.token.service.*;
import org.token.service.compute.ComputeManagementService;
import org.token.util.PasswordUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 经过真实方法安全代理校验附加字段授权；不创建账号、不连接数据库。 */
class SystemAccountPermissionTest {
    private AnnotationConfigApplicationContext context;
    private SystemController controller;

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean SystemController controller() {
            return new SystemController(new org.token.service.admin.AdminAccountManagementService(mock(AccountService.class), mock(AccountProfileService.class), mock(RoleService.class), mock(OperationLogService.class), mock(MenuService.class), mock(PasswordUtils.class), new LogActionCatalog(), org.mockito.Mockito.mock(org.token.repository.system.RoleRepository.class), mock(ComputeManagementService.class)), mock(ResultFactory.class));
        }
    }

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(SystemController.class);
    }

    @AfterEach void close() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    private void login(String... codes) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", null, AuthorityUtils.createAuthorityList(codes)));
    }

    @Test void ordinaryAccountCreateCannotSmuggleBindingOrDisabledState() {
        login("60201");
        AccountCreateRequest request = new AccountCreateRequest();
        // 空用户名由业务校验返回，证明请求已通过方法授权，而非实际新增账号。
        assertDoesNotThrow(() -> controller.accountCreate(request));
        request.setAgentMemberIds(List.of(10L));
        assertThrows(AccessDeniedException.class, () -> controller.accountCreate(request));
        request.setAgentMemberIds(null);
        request.setState(0);
        assertThrows(AccessDeniedException.class, () -> controller.accountCreate(request));
        request.setState(1);
        assertDoesNotThrow(() -> controller.accountCreate(request));
    }

    @Test void bindingAndStateNeedTheirExplicitLeavesOrSuper() {
        AccountCreateRequest request = new AccountCreateRequest();
        request.setAgentMemberIds(List.of(10L, 20L));
        request.setState(0);
        login("60201", "60206");
        assertThrows(AccessDeniedException.class, () -> controller.accountCreate(request));
        login("60201", "60206", "60205");
        assertDoesNotThrow(() -> controller.accountCreate(request));
        login("602");
        assertThrows(AccessDeniedException.class, () -> controller.accountCreate(request));
        login("super");
        assertDoesNotThrow(() -> controller.accountCreate(request));
    }

    @Test void clearingBindingAlsoRequiresBindingPermissionAndDoesNotReplaceEditPermission() {
        AccountUpdateRequest request = new AccountUpdateRequest();
        request.setAgentMemberIds(List.of());
        login("60202");
        assertThrows(AccessDeniedException.class, () -> controller.accountUpdate(request));
        login("60206");
        assertThrows(AccessDeniedException.class, () -> controller.accountUpdate(request));
        login("60202", "60206");
        // 授权通过后才触发缺少账号编号的业务校验。
        assertThrows(BusinessException.class, () -> controller.accountUpdate(request));
    }
}
