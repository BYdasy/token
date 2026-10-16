package org.token.service.compute;

import org.token.config.EnabledLocaleResolver;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.controller.user.ComputeUserController;
import org.token.repository.compute.ComputeData;
import org.token.model.security.ComputeMemberPrincipal;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 新购、分页与首页订单均保留用户金额，禁止输出内部订阅报价快照。 */
class ComputeUserOrderViewTest {
    @Test void orderPageProvidesUtcClockWithoutRecognizingUnpaidMaturityIncome() {
        var catalog = mock(ComputeCatalogService.class);
        var business = mock(ComputeBusinessService.class);
        var data = mock(ComputeData.class);
        var service = new ComputeUserApplicationService(business, catalog, data,
                mock(ComputeWithdrawalCoordinator.class), mock(org.token.repository.compute.ComputeControllerQueryRepository.class));
        var principal = new ComputeMemberPrincipal(1, "owner");
        var now = java.time.Instant.now();
        Map<String,Object> order = Map.of("durationDays",365,"settlementMode","A",
                "startsAt",now.minusSeconds(49*3600).toString(),"settledDays",0,"paidIncome","0",
                "expectedIncome","1405.25","status","ACTIVE");
        Map<String,Object> originalPage = Map.of("records",List.of(order),"total",1);
        when(catalog.page("orders",1L,Map.of())).thenReturn(originalPage);
        var results = mock(ResultFactory.class);
        when(results.create(org.mockito.ArgumentMatchers.eq(0),any(),org.mockito.ArgumentMatchers.eq("server.success")))
                .thenAnswer(call -> new Result(0,call.getArgument(1),"成功"));
        var controller = new ComputeUserController(service,results);
        var before = java.time.Instant.now();
        var page = (Map<?,?>) controller.page(principal,"orders",Map.of()).getData();
        var observed = java.time.Instant.parse((String) page.get("serverTime"));
        assertFalse(observed.isBefore(before));
        assertFalse(observed.isAfter(java.time.Instant.now()));
        assertTrue(page.get("serverTime").toString().endsWith("Z"));
        assertEquals(order,((List<?>)page.get("records")).get(0));
        assertFalse(originalPage.containsKey("serverTime"));
        verifyNoInteractions(business);
        // 原计价组件初始化会取得Mapper；订单展示不执行资金业务或数据库写入。
        verify(data).mapper(org.token.repository.dao.compute.ComputeUsdAccountingMapper.class);
        verifyNoMoreInteractions(data);

        when(catalog.page("ledger",1L,Map.of())).thenReturn(Map.of("records",List.of(),"total",0));
        var ledger = (Map<?,?>) controller.page(principal,"ledger",Map.of()).getData();
        assertFalse(ledger.containsKey("serverTime"));
        assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class,
                () -> controller.page(null,"orders",Map.of()));
    }

    @Test void allUserOrderEntrypointsHideInternalSnapshotAndPreservePriceBreakdown() {
        ComputeBusinessService business = mock(ComputeBusinessService.class);
        ComputeCatalogService catalog = mock(ComputeCatalogService.class);
        ResultFactory results = mock(ResultFactory.class);
        when(results.create(org.mockito.ArgumentMatchers.eq(0), any(), org.mockito.ArgumentMatchers.eq("server.success"))).thenAnswer(call -> new Result(0, call.getArgument(1), "成功"));
        Map<String, Object> order = Map.of("orderNo","PO-visible", "amount","200", "productQuoteJson","{\"internal\":true}");
        when(business.buyOrder(1, Map.of())).thenReturn(order);
        when(business.dashboard(1)).thenReturn(Map.of("orders", List.of(order)));
        when(catalog.page("orders", 1L, Map.of())).thenReturn(Map.of("records", List.of(order), "total", 1));
        var controller = new ComputeUserController(new org.token.service.compute.ComputeUserApplicationService(business, catalog, mock(ComputeData.class), mock(ComputeWithdrawalCoordinator.class), org.mockito.Mockito.mock(org.token.repository.compute.ComputeControllerQueryRepository.class)), results);
        var principal = new ComputeMemberPrincipal(1, "owner");
        Map<?, ?> bought = (Map<?, ?>) controller.buy(principal, Map.of()).getData();
        Map<?, ?> dashboard = (Map<?, ?>) ((List<?>) ((Map<?, ?>) controller.dashboard(principal).getData()).get("orders")).get(0);
        Map<?, ?> paged = (Map<?, ?>) ((List<?>) ((Map<?, ?>) controller.page(principal, "orders", Map.of()).getData()).get("records")).get(0);
        for (Map<?, ?> response : List.of(bought, dashboard, paged)) {
            assertFalse(response.containsKey("productQuoteJson"));
            assertEquals("200", response.get("amount"));
        }
        assertTrue(order.containsKey("productQuoteJson"));
    }
}
