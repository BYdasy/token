package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅本地 H2：验证提醒不会越代理范围，重复同步不制造新事件，不调用支付网关。 */
class ComputeFinanceAttentionServiceTest {
    private JdbcTemplate jdbc;
    private ComputeFinanceAttentionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:attention_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE compute_deposit(id BIGINT PRIMARY KEY,member_id BIGINT,order_no VARCHAR(48),status VARCHAR(24),record_source VARCHAR(24),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_withdrawal(id BIGINT PRIMARY KEY,member_id BIGINT,order_no VARCHAR(48),status VARCHAR(24),created_at TIMESTAMP,attention_ignored TINYINT DEFAULT 0)");
        // 充值提醒只使用当前逐笔订单及独立归集状态。
        jdbc.execute("CREATE TABLE compute_mcag_deposit_order(external_order_id VARCHAR(128),member_id BIGINT,state VARCHAR(24),reason VARCHAR(64),resource_version BIGINT,merchant_id VARCHAR(128),environment VARCHAR(16),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_mcag_collection_batch(batch_id VARCHAR(100),member_id BIGINT,state VARCHAR(24),sweep_status VARCHAR(24),reason VARCHAR(64),resource_version BIGINT,merchant_id VARCHAR(100),environment VARCHAR(20),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_mcag_payout(merchant_order_id VARCHAR(48),withdrawal_id BIGINT,state VARCHAR(24),remote_status VARCHAR(32),reason VARCHAR(128),resource_version BIGINT,merchant_id VARCHAR(100),environment VARCHAR(20),updated_at TIMESTAMP)");
        var scope = mock(ComputeAgentScope.class);
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,inviter_id BIGINT)");
        jdbc.update("INSERT INTO compute_member(id) VALUES(1),(2)");
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(1L)));
        var config = new ComputeMcagProperties();
        config.setEnabled(true); config.setMerchantId("merchant-test"); config.setEnvironment("LIVE");
        service = new ComputeFinanceAttentionService(new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules()), scope, config);
    }

    @Test
    void pendingAndAbnormalRecordsRespectMemberAndMerchantScope() {
        jdbc.update("INSERT INTO compute_deposit VALUES(1,1,'D1','PENDING','ADMIN_MANUAL',CURRENT_TIMESTAMP),(2,2,'D2','PENDING','ADMIN_MANUAL',CURRENT_TIMESTAMP),(3,1,'D3','PENDING','MCAG',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_mcag_collection_batch VALUES('batch1',1,'CREDITED','FAILED','SWEEP_FAILED',3,'merchant-test','LIVE',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_mcag_deposit_order VALUES('order1',1,'REVIEW','CHAIN_REVERSED',2,'merchant-test','LIVE',CURRENT_TIMESTAMP),('order2',2,'REVIEW','CHAIN_REVERSED',2,'merchant-test','LIVE',CURRENT_TIMESTAMP),('order3',1,'REVIEW','CHAIN_REVERSED',2,'other-merchant','LIVE',CURRENT_TIMESTAMP),('order4',1,'REVIEW','CHAIN_REVERSED',2,'merchant-test','SANDBOX',CURRENT_TIMESTAMP),('order5',1,'CREDITED','',2,'merchant-test','LIVE',CURRENT_TIMESTAMP)");
        var rows = records(service.snapshot(false));
        assertEquals(3, rows.size());
        assertEquals(java.util.Set.of("D1", "batch1", "order1"), rows.stream().map(row -> row.get("reference")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(rows.stream().noneMatch(row -> row.containsKey("memberId") || row.containsKey("id")));
        var order = rows.stream().filter(row -> "order1".equals(row.get("reference"))).findFirst().orElseThrow();
        assertEquals("DEPOSIT_ORDER", order.get("source"));
        assertEquals("REVIEW", order.get("attentionState"));
    }

    @Test
    void pollTimestampDoesNotChangeEventKeyButAuthoritativeVersionDoes() {
        jdbc.update("INSERT INTO compute_withdrawal(id,member_id,order_no,status,created_at) VALUES(1,1,'W1','PROCESSING',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_mcag_payout VALUES('W1',1,'REVIEW','UNKNOWN','UNCERTAIN',1,'merchant-test','LIVE',TIMESTAMP '2026-09-17 00:00:00')");
        var first = records(service.snapshot(true)).get(0);
        assertEquals("UNKNOWN", first.get("attentionState"));
        jdbc.update("UPDATE compute_mcag_payout SET updated_at=TIMESTAMP '2026-09-17 02:00:00'");
        assertEquals(first.get("eventKey"), records(service.snapshot(true)).get(0).get("eventKey"));
        jdbc.update("UPDATE compute_mcag_payout SET resource_version=2,remote_status='FAILED'");
        var changed = records(service.snapshot(true)).get(0);
        assertEquals("FAILED", changed.get("attentionState"));
        assertNotEquals(first.get("eventKey"), changed.get("eventKey"));
        jdbc.update("UPDATE compute_mcag_payout SET state='RELEASED'");
        assertTrue(records(service.snapshot(true)).isEmpty());
    }

    @Test
    void ignoreSuppressesManualAndSubmittedPayoutRemindersWithoutChangingPayment() {
        jdbc.update("INSERT INTO compute_withdrawal(id,member_id,order_no,status,created_at) VALUES(1,1,'W1','PENDING',CURRENT_TIMESTAMP),(2,1,'W2','PROCESSING',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_mcag_payout VALUES('W2',2,'REVIEW','UNKNOWN','UNCERTAIN',1,'merchant-test','LIVE',CURRENT_TIMESTAMP)");
        assertEquals(2, records(service.snapshot(true)).size());
        jdbc.update("UPDATE compute_withdrawal SET attention_ignored=1");
        assertTrue(records(service.snapshot(true)).isEmpty());
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT remote_status FROM compute_mcag_payout",String.class));
        jdbc.update("UPDATE compute_withdrawal SET attention_ignored=0 WHERE id=2");
        assertEquals(1, records(service.snapshot(true)).size());
    }

    @Test
    void largeQueueHasExplicitBoundAndNeverRequiresMoneyFields() {
        for (int index = 1; index <= 505; index++)
            jdbc.update("INSERT INTO compute_withdrawal(id,member_id,order_no,status,created_at) VALUES(?,1,?,'PENDING',CURRENT_TIMESTAMP)", index, "W" + index);
        var snapshot = service.snapshot(true);
        assertEquals(true, snapshot.get("truncated"));
        assertEquals(500, records(snapshot).size());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> snapshot) {
        return (List<Map<String, Object>>) snapshot.get("records");
    }
}
