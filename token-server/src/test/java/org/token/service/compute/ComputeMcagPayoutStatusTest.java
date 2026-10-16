package org.token.service.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.config.compute.ComputeMcagProperties;
import org.token.controller.admin.ComputeMcagController;
import org.token.exception.BusinessException;
import org.token.model.response.OperationOutcome;
import org.token.repository.compute.ComputeMcagPaymentGatewayRepository;
import org.token.repository.support.FixtureComputeData;
import org.token.tools.response.ResultFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实注解 Mapper 与内存数据验证异常提款分页，不连接外部网关或真实数据库。 */
class ComputeMcagPayoutStatusTest {
    private static final String MERCHANT = "mch_token";
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 10, 6, 12, 0);
    private JdbcTemplate jdbc;
    private ComputeMcagPaymentGateway gateway;
    private ComputeMcagPaymentGatewayRepository repository;
    private ComputeMcagClient client;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:payout_status_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY, username VARCHAR(40) NOT NULL)");
        jdbc.execute("CREATE TABLE compute_withdrawal(id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL)");
        TokenTestSchema.create(jdbc,"compute_mcag_payout");
        jdbc.update("INSERT INTO compute_member(id,username) VALUES(1,'payout_user')");
        for (int id = 1; id <= 125; id++) insert(id, MERCHANT, "LIVE", "REVIEW", "", UPDATED_AT);
        // 非 REVIEW 但有原因的记录仍沿用原异常筛选规则；更新时间优先于 id。
        insert(126, MERCHANT, "LIVE", "RETRY_WAIT", "MERCHANT_POOL_UNAVAILABLE", UPDATED_AT.plusSeconds(1));
        insert(201, "other_merchant", "LIVE", "REVIEW", "", UPDATED_AT.plusSeconds(2));
        insert(202, MERCHANT, "SANDBOX", "REVIEW", "", UPDATED_AT.plusSeconds(2));
        insert(203, MERCHANT, "LIVE", "SUBMITTED", "", UPDATED_AT.plusSeconds(2));
        insert(204, MERCHANT, "LIVE", "REVIEW", "", UPDATED_AT.plusSeconds(2));
        jdbc.update("UPDATE compute_withdrawal SET member_id=999 WHERE id=204");

        var data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        repository = spy(new ComputeMcagPaymentGatewayRepository(data));
        var config = new ComputeMcagProperties();
        config.setMerchantId(MERCHANT);
        config.setEnvironment("LIVE");
        client = mock(ComputeMcagClient.class);
        var target = new ComputeMcagPaymentGateway(config, client, mock(ComputeMcagDepositService.class),
                mock(ComputeCurrencyService.class), data, mock(ComputeMcagPayoutService.class),
                mock(ComputeMcagCollectionService.class), mock(ComputeMcagReceiptService.class),
                mock(ComputeMcagChannelService.class), repository);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(source));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        gateway = (ComputeMcagPaymentGateway) proxy.getProxy();
    }

    @Test
    void pagesBeyondOneHundredKeepExactTotalsAndStableOrderForEqualTimestamps() {
        List<String> actual = new ArrayList<>();
        for (int page = 1; page <= 7; page++) {
            var result = gateway.payoutStatus(page, 20);
            assertEquals("LIVE", result.get("environment"));
            assertEquals(page, result.get("page"));
            assertEquals(20, result.get("pageSize"));
            assertEquals(126L, result.get("total"));
            var rows = rows(result);
            assertEquals(page == 7 ? 6 : 20, rows.size());
            rows.forEach(row -> actual.add((String) row.get("localOrderNo")));
        }
        List<String> expected = IntStream.iterate(126, id -> id > 0, id -> id - 1)
                .mapToObj(id -> "withdrawal-" + id).toList();
        assertEquals(expected, actual);
        assertEquals(126, actual.stream().distinct().count());
        verifyNoInteractions(client);
    }

    @Test
    void maximumPageSizeAndEmptyPagesKeepTotalWithoutOffsetOverflow() {
        assertEquals(100, rows(gateway.payoutStatus(1, 100)).size());
        var second = gateway.payoutStatus(2, 100);
        assertEquals(26, rows(second).size());
        assertEquals("withdrawal-26", rows(second).get(0).get("localOrderNo"));
        for (int page : List.of(3, Integer.MAX_VALUE)) {
            var empty = gateway.payoutStatus(page, 100);
            assertEquals(126L, empty.get("total"));
            assertTrue(rows(empty).isEmpty());
            assertEquals(page, empty.get("page"));
        }
        verifyNoInteractions(client);
    }

    @Test
    void currentMerchantAndEnvironmentApplyToBothCountAndRows() {
        var result = gateway.payoutStatus(1, 100);
        assertEquals(126L, result.get("total"));
        assertEquals("withdrawal-126", rows(result).get(0).get("localOrderNo"));
        assertEquals("RETRY_WAIT", rows(result).get(0).get("state"));
        assertEquals("MERCHANT_POOL_UNAVAILABLE", rows(result).get(0).get("reason"));
        assertEquals(1L, rows(result).get(0).get("uid"));
        assertEquals("payout_user", rows(result).get(0).get("username"));
        assertEquals("EARNINGS", rows(result).get(0).get("walletType"));
        assertEquals(1L, repository.payoutStatusMcagPayoutCount("other_merchant", "LIVE"));
        assertEquals("withdrawal-201", repository.payoutStatusMcagPayoutRows("other_merchant", "LIVE", 20, 0)
                .get(0).get("localOrderNo"));
        assertEquals(1L, repository.payoutStatusMcagPayoutCount(MERCHANT, "SANDBOX"));
        assertEquals(0L, repository.payoutStatusMcagPayoutCount("mch_token' OR 1=1 --", "LIVE"));
        assertTrue(repository.payoutStatusMcagPayoutRows("mch_token' OR 1=1 --", "LIVE", 20, 0).isEmpty());
    }

    @Test
    void countAndRowsRunInOneReadOnlyRepeatableReadTransaction() {
        List<Object> transactionResources = new ArrayList<>();
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            transactionResources.addAll(TransactionSynchronizationManager.getResourceMap().values());
            return invocation.callRealMethod();
        }).when(repository).payoutStatusMcagPayoutCount(MERCHANT, "LIVE");
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertTrue(TransactionSynchronizationManager.getResourceMap().values().containsAll(transactionResources));
            return invocation.callRealMethod();
        }).when(repository).payoutStatusMcagPayoutRows(MERCHANT, "LIVE", 20, 0);
        assertEquals(20, rows(gateway.payoutStatus(1, 20)).size());
        assertFalse(transactionResources.isEmpty());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        verifyNoInteractions(client);
    }

    @Test
    void invalidBoundsAreRejectedBeforeDatabaseQueries() {
        for (int[] pagination : List.of(new int[]{0, 20}, new int[]{-1, 20}, new int[]{1, 0},
                new int[]{1, -1}, new int[]{1, 101}, new int[]{1, Integer.MAX_VALUE})) {
            var failure = assertThrows(BusinessException.class,
                    () -> gateway.payoutStatus(pagination[0], pagination[1]));
            assertEquals("server.compute.request.invalid", failure.getMessage());
        }
        verifyNoInteractions(repository, client);
    }

    @Test
    void globalScopeIsRequiredBeforeQueryingTheGateway() {
        var scopedGateway = mock(ComputeMcagPaymentGateway.class);
        var scope = mock(ComputeAgentScope.class);
        var service = new ComputeMcagAdminService(scopedGateway, scope, mock(ComputeMerchantService.class));
        var denied = new BusinessException(403, "server.permission.denied");
        doThrow(denied).when(scope).requireGlobalScope();
        assertSame(denied, assertThrows(BusinessException.class, () -> service.payoutStatus(1, 20)));
        verifyNoInteractions(scopedGateway);
        doNothing().when(scope).requireGlobalScope();
        var result = Map.<String, Object>of("payoutReview", List.of(), "total", 0L);
        when(scopedGateway.payoutStatus(3, 40)).thenReturn(result);
        assertSame(result, service.payoutStatus(3, 40).data());
        verify(scopedGateway).payoutStatus(3, 40);
    }

    @Test
    void controllerUsesDefaultsAndForwardsExplicitPagination() throws Exception {
        var service = mock(ComputeMcagAdminService.class);
        when(service.payoutStatus(anyInt(), anyInt())).thenReturn(OperationOutcome.success(Map.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new ComputeMcagController(service, mock(ResultFactory.class))).build();
        mvc.perform(get("/api/compute/admin/gateway/payouts/status")).andExpect(status().isOk());
        verify(service).payoutStatus(1, 20);
        mvc.perform(get("/api/compute/admin/gateway/payouts/status").param("page", "6").param("pageSize", "25"))
                .andExpect(status().isOk());
        verify(service).payoutStatus(6, 25);
    }

    @Test
    void controllerRejectsMalformedAndOverflowingNumbersBeforeCallingService() throws Exception {
        var service = mock(ComputeMcagAdminService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ComputeMcagController(service, mock(ResultFactory.class))).build();
        for (String parameter : List.of("page", "pageSize")) {
            for (String value : List.of("abc", "1.5", "2147483648")) {
                mvc.perform(get("/api/compute/admin/gateway/payouts/status").param(parameter, value))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(service);
    }

    private void insert(long id, String merchant, String environment, String state, String reason, LocalDateTime updatedAt) {
        jdbc.update("INSERT INTO compute_withdrawal(id,member_id) VALUES(?,1)", id);
        jdbc.update("INSERT INTO compute_mcag_payout(id,merchant_id,environment,withdrawal_id,merchant_order_id,customer_id,currency,network,asset_id,network_id,destination_address,amount_text,state,reason,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,'customer-1','USDT','TRC20','USDT_TRON','TRON','fixture-destination','10.00',?,?,?,?,?)",
                id, merchant, environment, id, "withdrawal-" + id, state, reason, UPDATED_AT, UPDATED_AT, updatedAt);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("payoutReview");
    }
}
