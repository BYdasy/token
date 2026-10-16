package org.token.controller.admin;

import org.token.custom.LogActionCatalog;
import org.token.model.request.system.ActionQueryRequest;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.AccountProfileService;
import org.token.service.AccountService;
import org.token.service.MenuService;
import org.token.service.OperationLogService;
import org.token.service.RoleService;
import org.token.util.PasswordUtils;
import org.token.service.compute.ComputeManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemControllerTest {

    @Mock
    private AccountService accountService;
    @Mock
    private AccountProfileService accountProfileService;
    @Mock
    private RoleService roleService;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private MenuService menuService;
    @Mock
    private ResultFactory resultFactory;
    @Mock
    private PasswordUtils passwordUtils;
    @Mock
    private ComputeManagementService computeManagement;

    private SystemController controller;

    @BeforeEach
    void setUp() {
        controller = new SystemController(new org.token.service.admin.AdminAccountManagementService(accountService, accountProfileService, roleService, operationLogService, menuService, passwordUtils, new LogActionCatalog(), org.mockito.Mockito.mock(org.token.repository.system.RoleRepository.class), computeManagement), resultFactory);
        when(resultFactory.create(org.mockito.ArgumentMatchers.eq(0), any(), org.mockito.ArgumentMatchers.eq("server.success"))).thenReturn(new Result());
    }

    @Test
    void actionOptionsIncludeCurrentAndHistoricalTypesWithoutDuplicates() {
        when(operationLogService.actionTypes()).thenReturn(List.of("会员课程预约", "模板新增"));
        controller.systemLogActions(null);

        List<String> actions = capturedActions();
        assertEquals(new LogActionCatalog().getSupportedActions().size() + 1, actions.size());
        assertTrue(actions.contains("算力用户资金调整"));
        assertTrue(actions.contains("模板新增"));
        assertTrue(actions.contains("会员课程预约"));
        verify(operationLogService).actionTypes();
    }

    @Test
    void actionOptionsSupportKeywordSearch() {
        ActionQueryRequest request = new ActionQueryRequest();
        request.setQ("模板");

        controller.systemLogActions(request);

        List<String> actions = capturedActions();
        assertFalse(actions.isEmpty());
        assertFalse(actions.stream().anyMatch(action -> !action.contains("模板")));
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedActions() {
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(resultFactory).create(org.mockito.ArgumentMatchers.eq(0), data.capture(), org.mockito.ArgumentMatchers.eq("server.success"));
        return (List<String>) data.getValue();
    }
}
