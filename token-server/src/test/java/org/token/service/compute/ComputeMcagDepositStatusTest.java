package org.token.service.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeMcagPaymentGatewayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在隔离 H2 中执行真实分页 SQL；JSON 兼容函数仅用于模拟 MySQL 公共快照读取。 */
public class ComputeMcagDepositStatusTest {
    private ComputeMcagFixture fixture;
    private ComputeMcagPaymentGatewayRepository repository;
    private ComputeMcagPaymentGateway gateway;
    private ComputeMcagClient client;
    private ComputeMcagDepositService deposits;
    private static final LocalDateTime TIME = LocalDateTime.of(2026,10,7,1,0);

    @BeforeEach void setup() throws Exception {
        fixture = new ComputeMcagFixture(); fixture.setup();
        for (String[] function : List.of(new String[]{"JSON_VALID","jsonValid"}, new String[]{"JSON_EXTRACT","jsonExtract"}, new String[]{"JSON_UNQUOTE","jsonUnquote"}))
            fixture.jdbc.execute("CREATE ALIAS " + function[0] + " FOR '" + getClass().getName() + "." + function[1] + "'");
        repository = spy(new ComputeMcagPaymentGatewayRepository(fixture.data));
        client = mock(ComputeMcagClient.class); deposits = mock(ComputeMcagDepositService.class);
        var target = new ComputeMcagPaymentGateway(fixture.config, client, deposits, mock(ComputeCurrencyService.class),
                fixture.data, mock(ComputeMcagPayoutService.class), mock(ComputeMcagCollectionService.class),
                mock(ComputeMcagReceiptService.class), mock(ComputeMcagChannelService.class), repository);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); proxy.addAdvice(advice);
        gateway = (ComputeMcagPaymentGateway) proxy.getProxy();
    }

    @Test void pagesBeyondOneHundredHaveStableOrderAndSharedExactCount() {
        for (int id=1; id<=125; id++) order(id, 1, "AMOUNT_PRECISION", null, null);
        List<String> actual = new ArrayList<>();
        for (int page=1; page<=7; page++) {
            var result = gateway.status(page,20);
            assertEquals(125L,result.get("total")); assertEquals(page,result.get("page")); assertEquals(20,result.get("pageSize"));
            assertEquals("LIVE",result.get("environment")); assertEquals(0L,result.get("afterSequence"));
            assertEquals(page==7?5:20,rows(result).size());
            rows(result).forEach(row->actual.add(text(row.get("gatewayReference"))));
        }
        var expected = new ArrayList<>(actual); expected.sort(Comparator.reverseOrder());
        assertEquals(expected,actual); assertEquals(125,actual.stream().distinct().count());
        assertEquals(100,rows(gateway.status(1,100)).size());
        var empty=gateway.status(Integer.MAX_VALUE,100);
        assertEquals(125L,empty.get("total")); assertTrue(rows(empty).isEmpty());
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_cursor",Integer.class));
        verifyNoInteractions(client,deposits);
    }

    @Test void excludesNormalTestsWaitingAndServiceFeesButKeepsPrecisionAndIncorrectManualLink() {
        order(1,1,"AMOUNT_PRECISION",null,null);
        order(2,2,"RISK_REVIEW",null,null);
        order(3,1,"TEST_MEMBER_NOT_ALLOWED",null,null);
        order(4,1,"CHAIN_PENDING",null,null);
        long manual=local("ADMIN_MANUAL",1);
        order(5,1,"RISK_REVIEW",manual,null);
        long linkedTest=local("MCAG",2);
        order(6,1,"RISK_REVIEW",linkedTest,null);
        batch(7,"RECEIPT_SWEEP","{\"purpose\":\"SERVICE_FEE\"}",1,"COLLECTION_FAILED");
        batch(8,"RECEIPT_SWEEP","{}",1,"COLLECTION_FAILED");
        batch(9,"RECEIPT_SWEEP","{\"purpose\":\"CUSTOMER_DEPOSIT\"}",2,"TEST_MEMBER_NOT_ALLOWED");
        var result=gateway.status(1,20);
        assertEquals(2L,result.get("total"));
        var manualLink=rows(result).stream().filter(row->"order-5".equals(row.get("gatewayReference"))).findFirst().orElseThrow();
        assertEquals(false,manualLink.get("canReconcile")); assertEquals(false,manualLink.get("canRecover"));
        var row=rows(result).stream().filter(item->"order-1".equals(item.get("gatewayReference"))).findFirst().orElseThrow();
        assertEquals("order-1",row.get("gatewayReference")); assertEquals("AMOUNT_PRECISION",row.get("reason"));
        assertEquals("DEPOSIT_ORDER",row.get("resourceType")); assertEquals("SINGLE_RECEIPT",row.get("accountingModel"));
        assertEquals(true,row.get("canReconcile")); assertEquals(true,row.get("canRecover"));
        assertEquals(true,row.get("missingLocalOrder")); assertEquals(false,row.get("credited"));
        assertEquals("USDT",row.get("currency")); assertEquals("TRC20",row.get("network")); assertEquals("COMPUTE",row.get("walletType"));
    }

    @Test void creditedReorgIsStillVisibleAfterSwitchingToTestOrUnexpectedRecordSource() {
        long credited=local("MCAG",2);
        order(1,2,"TEST_MEMBER_NOT_ALLOWED",credited,credited);
        fixture.jdbc.update("UPDATE compute_mcag_deposit_order SET remote_status='REVIEW' WHERE external_order_id='order-1'");
        order(2,2,"TEST_MEMBER_NOT_ALLOWED",credited,credited);
        fixture.jdbc.update("UPDATE compute_mcag_deposit_order SET snapshot_json=? WHERE external_order_id='order-2'","{\"chain_status\":\"ORPHANED\"}");
        long manual=local("ADMIN_MANUAL",1);
        order(3,1,"CHAIN_REVERSED",manual,manual);
        fixture.jdbc.update("UPDATE compute_mcag_deposit_order SET remote_status='REVIEW' WHERE external_order_id='order-3'");
        var result=gateway.status(1,20); assertEquals(3L,result.get("total"));
        for(var row:rows(result)) {
            assertEquals("CHAIN_REVERSED",row.get("reason")); assertEquals(true,row.get("credited"));
            assertEquals(false,row.get("canRecover")); assertEquals(false,row.get("missingLocalOrder"));
            assertEquals(!"order-3".equals(row.get("gatewayReference")),row.get("canReconcile"));
        }
    }

    @Test void collectionRowsOnlyOfferSweepReconciliationAndNeverWalletRecovery() {
        batch(1,"RECEIPT_SWEEP","{ \"purpose\" : \"CUSTOMER_DEPOSIT\" }",1,"COLLECTION_FAILED");
        var result=gateway.status(1,20); assertEquals(1L,result.get("total"));
        for(var row:rows(result)) {
            assertEquals(false,row.get("canRecover"));
            assertEquals("batch-1".equals(row.get("gatewayReference")),row.get("canReconcile"));
        }
    }

    @Test void danglingLinksAndUnknownReasonsNeverPromiseRecovery() {
        order(1,1,"AMOUNT_PRECISION",999L,null);
        long first=local("MCAG",1),second=local("MCAG",1);
        order(4,1,"RISK_HELD",first,second);
        order(5,1,"FUTURE_UNKNOWN_REASON",null,null);
        order(6,1,"DISABLED_ADDRESS",null,null);
        order(7,1,"",null,null);
        var result=gateway.status(1,20);assertEquals(5L,result.get("total"));
        for(var row:rows(result)) {
            assertEquals(false,row.get("canRecover"));
            assertEquals(!"order-4".equals(row.get("gatewayReference")),row.get("canReconcile"));
        }
    }

    @Test void tenantFilterAndPureCursorReadApplyToCountAndPage() {
        order(1,1,"AMOUNT_PRECISION",null,null); order(2,1,"AMOUNT_PRECISION",null,null); order(3,1,"AMOUNT_PRECISION",null,null);
        fixture.jdbc.update("UPDATE compute_mcag_deposit_order SET merchant_id='other' WHERE external_order_id='order-2'");
        fixture.jdbc.update("UPDATE compute_mcag_deposit_order SET environment='SANDBOX' WHERE external_order_id='order-3'");
        fixture.jdbc.update("INSERT INTO compute_mcag_cursor(merchant_id,environment,after_sequence,updated_at) VALUES('mch_token','LIVE',42,CURRENT_TIMESTAMP)");
        var result=gateway.status(1,20); assertEquals(1L,result.get("total")); assertEquals(42L,result.get("afterSequence"));
        assertEquals("order-1",rows(result).get(0).get("gatewayReference"));
        assertEquals(1L,repository.depositReviewCount("other","LIVE"));
        assertEquals(0L,repository.depositReviewCount("mch_token' OR 1=1 --","LIVE"));
        assertTrue(repository.depositReviews("mch_token' OR 1=1 --","LIVE",20,0).isEmpty());
    }

    @Test void countRowsAndCursorShareOneReadOnlyRepeatableReadTransaction() {
        order(1,1,"AMOUNT_PRECISION",null,null);
        doAnswer(invocation->{ assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return invocation.callRealMethod(); }).when(repository).depositReviewCount("mch_token","LIVE");
        doAnswer(invocation->{ assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return invocation.callRealMethod(); })
                .when(repository).depositReviews("mch_token","LIVE",20,0);
        doAnswer(invocation->{ assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return invocation.callRealMethod(); })
                .when(repository).depositAfterSequence("mch_token","LIVE");
        assertEquals(1,rows(gateway.status(1,20)).size()); verifyNoInteractions(deposits,client);
    }

    @Test void invalidPageBoundsAndScopedAdministratorsCannotQuery() {
        for(int[] bound:List.of(new int[]{0,20},new int[]{1,0},new int[]{-1,20},new int[]{1,101}))
            assertThrows(BusinessException.class,()->gateway.status(bound[0],bound[1]));
        verifyNoInteractions(repository,client,deposits);
        var scope=mock(ComputeAgentScope.class); var mockGateway=mock(ComputeMcagPaymentGateway.class);
        var service=new ComputeMcagAdminService(mockGateway,scope,mock(ComputeMerchantService.class));
        doThrow(new BusinessException(403,"server.permission.denied")).when(scope).requireGlobalScope();
        assertThrows(BusinessException.class,()->service.status(1,20)); verifyNoInteractions(mockGateway);
    }

    private long local(String source,long member) {
        return fixture.data.insert("compute_deposit",fields("member_id",member,"order_no",UUID.randomUUID().toString(),
                "wallet_type","COMPUTE","currency","USDT","network","TRC20","amount","10","status","APPROVED",
                "record_source",source,"created_at",TIME));
    }
    private void order(int id,long member,String reason,Long record,Long credited) {
        fixture.data.insert("compute_mcag_deposit_order",fields("merchant_id","mch_token","environment","LIVE","external_order_id","order-"+id,
                "receipt_id","receipt-"+id,"transfer_event_id","transfer-"+id,"customer_id","token_1_compute","address_id","addr_1",
                "asset_id","USDT_TRON","network_id","TRON","address","T1"+"a".repeat(32),"amount_text","10","tx_hash","hash-"+id,
                "transfer_locator","log:1","resource_version",1,"member_id",member,"state","REVIEW","reason",reason,
                "remote_status","CONFIRMED","record_id",record,"local_deposit_id",credited,"snapshot_json","{}","created_at",TIME,"updated_at",TIME));
    }
    private void batch(int id,String model,String json,Integer member,String reason) {
        fixture.data.insert("compute_mcag_collection_batch",fields("merchant_id","mch_token","environment","LIVE","batch_id","batch-"+id,
                "resource_version",1,"member_id",member,"customer_id","token_1_compute","address_id","addr_1","asset_id","USDT_TRON","network_id","TRON",
                "amount_text","10","state","REVIEW","reason",reason,"sweep_status","FAILED","collection_json",json,"accounting_model",model,"created_at",TIME,"updated_at",TIME));
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> result) {return (List<Map<String,Object>>)result.get("review");}
    public static boolean jsonValid(String value) {try {return value!=null&&new ObjectMapper().readTree(value)!=null;}catch(Exception ignored){return false;}}
    public static String jsonExtract(String value,String path) throws Exception {var field=new ObjectMapper().readTree(value).get(path.substring(2));return field==null||field.isNull()?null:field.toString();}
    public static String jsonUnquote(String value) throws Exception {return value==null?null:new ObjectMapper().readTree(value).asText();}
}
