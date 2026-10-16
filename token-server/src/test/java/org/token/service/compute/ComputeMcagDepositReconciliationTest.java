package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.model.request.compute.GatewayReconciliationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 权威查单使用假客户端，恢复使用真实服务事务与隔离账本，不连接任何外部服务。 */
class ComputeMcagDepositReconciliationTest {
    private ComputeMcagReceiptTest receipt;
    private ComputeMcagFixture fixture;
    private ComputeMcagClient client;
    private ComputeMcagPaymentGateway gateway;
    private ComputeMcagReceiptService receipts;
    private ComputeMcagCollectionService collections;

    @BeforeEach void setup() throws Exception {
        receipt=new ComputeMcagReceiptTest();receipt.setup();fixture=receipt.fixture;
        receipts=transactional(receipt.service); collections=transactional(receipt.collections);
        client=mock(ComputeMcagClient.class);
        gateway=new ComputeMcagPaymentGateway(fixture.config,client,fixture.deposits,new ComputeCurrencyService(fixture.data),
                fixture.data,mock(ComputeMcagPayoutService.class),collections,receipts,new ComputeMcagChannelService(fixture.data,fixture.config));
    }

    @Test void authorityReplayRestoresHighPrecisionMissingOrderOnceAndReportsExistingCredit() {
        var order=precisionOrder();seedPrecisionReview(order);
        authoritativeOrder(order);
        var result=gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT");
        assertEquals("CREDITED",result.get("state"));assertEquals("",result.get("reason"));
        assertEquals("DEPOSIT_ORDER",result.get("resourceType"));assertEquals("SINGLE_RECEIPT",result.get("accountingModel"));
        assertEquals(true,result.get("credited"));assertEquals(false,result.get("alreadyCredited"));assertEquals(true,result.get("orderCreated"));
        assertNotNull(result.get("localOrderNo"));
        var again=gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT");
        assertEquals(true,again.get("alreadyCredited"));assertEquals(result.get("localOrderNo"),again.get("localOrderNo"));
        assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));assertEquals(1,count("compute_finance_snapshot"));
        assertEquals(0,new BigDecimal("11.696130139510566704").compareTo(fixture.jdbc.queryForObject("SELECT amount FROM compute_deposit",BigDecimal.class)));
        assertEquals(0,new BigDecimal("11.69613013").compareTo(fixture.jdbc.queryForObject("SELECT amount FROM compute_ledger",BigDecimal.class)));
        var local=fixture.data.one("SELECT * FROM compute_deposit");
        var ledger=fixture.data.one("SELECT * FROM compute_ledger");
        assertEquals("DEPOSIT:"+local.get("id"),ledger.get("eventKey"));assertEquals(text(local.get("id")),ledger.get("referenceId"));
        verify(client,times(2)).request("deposit-orders/query",Map.of("order_id",order.get("order_id"),"limit",1));
        verify(fixture.messages,times(1)).depositApproved(anyLong(),eq(1L),any(),eq("USDT"));
    }

    @Test void concurrentClicksAndPollingReplayStillCreateOneLocalOrderAndLedger() throws Exception {
        var order=precisionOrder();seedPrecisionReview(order);authoritativeOrder(order);
        var pool=Executors.newFixedThreadPool(3);
        try {
            var calls=new ArrayList<Future<?>>();
            for(int i=0;i<6;i++) calls.add(pool.submit(()->gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT")));
            calls.add(pool.submit(()->receipts.apply(order,null)));
            for(var call:calls)call.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));assertEquals(1,count("compute_finance_snapshot"));
    }

    @Test void unconfirmedOrDisabledOrderIsVisibleWithoutClaimingMoneyWasCredited() {
        var order=receipt.order();order.put("finality_satisfied",false);authoritativeOrder(order);
        var result=gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT");
        assertEquals("REVIEW",result.get("state"));assertEquals("CHAIN_PENDING",result.get("reason"));
        assertEquals(false,result.get("credited"));assertEquals(true,result.get("orderCreated"));assertNotNull(result.get("localOrderNo"));
        fixture.jdbc.update("UPDATE compute_mcag_address SET disabled_at=CURRENT_TIMESTAMP");
        order.put("finality_satisfied",true);
        var disabled=gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT");
        assertEquals("DISABLED_ADDRESS",disabled.get("reason"));assertEquals(false,disabled.get("credited"));
        assertEquals(0,count("compute_ledger"));verifyNoInteractions(fixture.messages);
    }

    @Test void creditedThenTestAndReorgPreservesRiskReasonWithoutASecondCredit() {
        var order=receipt.order();authoritativeOrder(order);
        gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT");
        fixture.jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=1");
        order.putAll(fields("resource_version",2,"status","REVIEW","chain_status","ORPHANED","finality_satisfied",false));
        var result=gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT");
        assertEquals("REVIEW",result.get("state"));assertEquals("CHAIN_REVERSED",result.get("reason"));
        assertEquals(true,result.get("credited"));assertEquals(true,result.get("alreadyCredited"));assertEquals(1,count("compute_ledger"));
    }

    @Test void collectionReconciliationOnlyTracksSweepAndNeverCreatesDeposit() {
        var batch=receipt.batch("RECEIPT_SWEEP");batch.put("sweep_status","FAILED");
        collections.apply(batch,null);
        batch.putAll(fields("resource_version",2,"sweep_status","SUCCEEDED"));
        when(client.request("collection-batches/query",Map.of("batch_id",batch.get("batch_id"),"view","summary"))).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return Map.of("items",List.of(batch));});
        var result=gateway.requestReconciliation(text(batch.get("batch_id")),"DEPOSIT");
        assertEquals("TRACKED",result.get("state"));assertEquals("COLLECTION_BATCH",result.get("resourceType"));
        assertEquals(false,result.get("credited"));assertEquals(false,result.get("orderCreated"));assertNull(result.get("localOrderNo"));
        assertEquals(0,count("compute_deposit"));assertEquals(0,count("compute_ledger"));assertEquals(0,count("compute_finance_snapshot"));
        verify(client,never()).request(eq("deposit-orders/query"),anyMap());
    }

    @Test void batchWithUnexpectedProtocolRefusesRecoveryWithoutExternalRequests() {
        var batch=receipt.batch("RECEIPT_SWEEP");collections.apply(batch,null);
        fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET accounting_model='UNKNOWN'");
        assertEquals("server.compute.request.invalid",assertThrows(BusinessException.class,
                ()->gateway.requestReconciliation(text(batch.get("batch_id")),"DEPOSIT")).getMessage());
        verifyNoInteractions(client);assertEquals(0,count("compute_ledger"));
    }

    @Test void untrustedResponseAndTransactionFailureLeaveNoFalseSuccessOrPartialMoney() {
        var order=receipt.order();var wrong=new LinkedHashMap<>(order);wrong.put("merchant_id","another");
        authoritativeOrder(wrong);
        assertThrows(BusinessException.class,()->gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT"));
        assertEquals(0,count("compute_deposit"));assertEquals(0,count("compute_ledger"));
        authoritativeOrder(order);
        doThrow(new IllegalStateException("fixture rollback")).when(fixture.messages).depositApproved(anyLong(),anyLong(),any(),anyString());
        assertThrows(IllegalStateException.class,()->gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT"));
        assertEquals(0,count("compute_deposit"));assertEquals(0,count("compute_ledger"));assertEquals(0,count("compute_mcag_deposit_order"));
    }

    @Test void incorrectManualSourceOrConflictingPointersCannotBeReconciled() {
        var order=receipt.order();authoritativeOrder(order);gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT");
        clearInvocations(client);
        fixture.jdbc.update("UPDATE compute_deposit SET record_source='ADMIN_MANUAL'");
        assertThrows(BusinessException.class,()->gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT"));
        fixture.jdbc.update("UPDATE compute_deposit SET record_source='MCAG'");
        fixture.jdbc.update("UPDATE compute_mcag_deposit_order SET local_deposit_id=999");
        assertThrows(BusinessException.class,()->gateway.requestReconciliation(text(order.get("order_id")),"DEPOSIT"));
        verifyNoInteractions(client);assertEquals(1,count("compute_ledger"));
    }

    @Test void globalScopeAndFreshTotpPrecedeEveryDepositRecovery() {
        var scope=mock(ComputeAgentScope.class);var merchant=mock(ComputeMerchantService.class);var payment=mock(ComputePaymentGateway.class);
        var service=new ComputeGatewayAdminService(payment,scope,merchant);
        var request=new GatewayReconciliationRequest("deposit_1","DEPOSIT","authoritative recovery","123456");
        service.reconcile(request);
        var sequence=inOrder(scope,merchant,payment);
        sequence.verify(scope).requireGlobalScope();sequence.verify(merchant).requireSuperTotp("123456");
        sequence.verify(payment).requestReconciliation("deposit_1","DEPOSIT");
        clearInvocations(payment);
        doThrow(new BusinessException("server.security.totp.invalid")).when(merchant).requireSuperTotp("123456");
        assertThrows(BusinessException.class,()->service.reconcile(request));verifyNoInteractions(payment);
    }

    private Map<String,Object> precisionOrder() {
        ComputeMcagTestSchema.seedMapping(fixture.jdbc,"USDT","BEP20","USDT_BSC","BSC_MAINNET",18);
        var order=receipt.order();order.putAll(fields("asset_id","USDT_BSC","network_id","BSC_MAINNET","address_id","addr_bsc",
                "address","0x"+"a".repeat(40),"decimals",18,"amount","11.696130139510566704"));return order;
    }
    private void seedPrecisionReview(Map<String,Object> order) {
        fixture.data.insert("compute_mcag_deposit_order",fields("merchant_id","mch_token","environment","LIVE","external_order_id",order.get("order_id"),
                "receipt_id",order.get("receipt_id"),"transfer_event_id",order.get("transfer_event_id"),"customer_id",order.get("customer_id"),
                "address_id",order.get("address_id"),"asset_id",order.get("asset_id"),"network_id",order.get("network_id"),"address",order.get("address"),
                "amount_text",order.get("amount"),"tx_hash",order.get("tx_hash"),"transfer_locator",order.get("transfer_locator"),"resource_version",1,
                "member_id",1,"state","REVIEW","reason","AMOUNT_PRECISION","remote_status","CONFIRMED","snapshot_json","{}","created_at",now(),"updated_at",now()));
    }
    private void authoritativeOrder(Map<String,Object> order) {
        when(client.request("deposit-orders/query",Map.of("order_id",order.get("order_id"),"limit",1))).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return Map.of("items",List.of(order));});
    }
    private int count(String table) {return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @SuppressWarnings("unchecked") private <T> T transactional(T target) {
        var advice=new TransactionInterceptor();advice.setTransactionManager(new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(advice);return (T)proxy.getProxy();
    }
}
