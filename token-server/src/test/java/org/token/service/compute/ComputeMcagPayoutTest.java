package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 内存数据库与隔离网关验证真实生产代付状态机，不发送任何真实付款。 */
@Tag("regression")
class ComputeMcagPayoutTest {
    JdbcTemplate jdbc; TransactionTemplate tx; FixtureComputeData data; ComputeBusinessService business;
    ComputeMcagPayoutService payouts; ComputeMcagClient client; ComputeMcagProperties config;
    AtomicReference<Map<String,Object>> remote;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:payout_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000");
        var transactions=new DataSourceTransactionManager(source);jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(transactions);
        jdbc.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeWorkflowIntegrationTest.utcTimestamp'");
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run", "compute_mcag_payout");
        TokenTestSchema.apply(jdbc);
        ComputeMcagTestSchema.apply(jdbc);


        jdbc.update("INSERT INTO compute_member(id,username,password_hash,invite_code,is_test,created_at,updated_at) VALUES(1,'payout_user','unused','invite1',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'test_user','unused','invite2',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1)");
        TokenTestSchema.seedCurrencies(jdbc);
        ComputeMcagTestSchema.seedMapping(jdbc,"USDT","TRC20","USDT_TRON","TRON",6);
        data=new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules());
        config=new ComputeMcagProperties();config.setEnabled(true);config.setMerchantId("mch_token");config.setEnvironment("LIVE");
        var channel=new ComputeMcagProperties.Channel();channel.setCurrency("USDT");channel.setNetwork("TRC20");channel.setAssetId("USDT_TRON");channel.setNetworkId("TRON");config.getChannels().put("usdt-tron",channel);
        business=new ComputeBusinessService(data,new ComputeCurrencyService(data),new PasswordUtils(),mock(ComputeSystemMessageService.class),null,null,config,
                new ComputeFinanceSnapshotService(data),new ComputeMcagChannelService(data,config),mock(ComputeWithdrawalAddressGuard.class));
        client=mock(ComputeMcagClient.class);payouts=new ComputeMcagPayoutService(data,business,config,client,transactions);remote=new AtomicReference<>();
        when(client.request(anyString(),anyMap())).thenAnswer(invocation->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"外部支付请求不得持有业务事务");
            String operation=invocation.getArgument(0); Map<String,Object> body=invocation.getArgument(1);
            return switch(operation) {
                case "payouts/query" -> fields("items",remote.get()==null ? List.of() : List.of(remote.get()));
                case "assets/query" -> fields("items",List.of(fields("asset_id","USDT_TRON","network_id","TRON","symbol","USDT","decimals",6,"payout_enabled",true)));
                case "payouts/quote" -> fields("merchant_id","mch_token","environment","LIVE","quote_id","q1","customer_id",body.get("customer_id"),
                        "asset_id",body.get("asset_id"),"network_id",body.get("network_id"),"destination_address",body.get("destination_address"),"amount",body.get("amount"),
                        "status","OPEN","expires_at",Instant.now().plusSeconds(120).toString(),"max_fee_charge","1","fee_asset_id","USDT_TRON");
                case "payouts/create" -> {assertEquals(Set.of("quote_id","merchant_order_id","customer_id"),body.keySet());remote.set(response("QUEUED",1,false));yield remote.get();}
                default -> throw new AssertionError(operation);
            };
        });
    }
    private long pending(long member,String net) {
        return tx.execute(status->{
            business.initializeWallets(member);
            jdbc.update("UPDATE compute_wallet SET available=900,frozen=100 WHERE member_id=? AND wallet_type='EARNINGS' AND currency='USDT'",member);
            return data.insert("compute_withdrawal",fields("member_id",member,"currency","USDT","network","TRC20","amount","100","fee",new BigDecimal("100").subtract(new BigDecimal(net)),
                    "net_amount",net,"address","T"+"a".repeat(33),"address_snapshot",data.json(fields("address","T"+"a".repeat(33),"memo","","fields",Map.of())),"rule_snapshot","{}","status","PENDING","created_at",now()));
        });
    }
    private long approve() {long id=pending(1,"97");tx.executeWithoutResult(status->business.reviewWithdrawal(id,true,"批准",1));return id;}
    private Map<String,Object> response(String status,long version,boolean safe) {
        var job=data.one("SELECT * FROM compute_mcag_payout");
        return fields("merchant_id","mch_token","environment","LIVE","merchant_order_id",job.get("merchantOrderId"),"customer_id",job.get("customerId"),
                "payout_id","po1","quote_id",job.get("quoteId"),"asset_id","USDT_TRON","network_id","TRON","destination_address",job.get("destinationAddress"),
                "amount",job.get("amountText"),"status",status,"resource_version",version,"safe_to_release_user_principal",safe);
    }
    private BigDecimal balance(String name) {return jdbc.queryForObject("SELECT "+name+" FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS' AND currency='USDT'",BigDecimal.class);}
    private void due() {jdbc.update("UPDATE compute_mcag_payout SET next_attempt_at=?",now().minusSeconds(1));}
    /** DATETIME(6) 会舍入纳秒；显式到期后调度，避免同一系统时钟刻度下尚未认领任务。 */
    private void dispatchDue() {due();payouts.dispatch();}
    @Test void approvalOnlyEnqueuesAndSuccessConsumesFrozenOnce() {
        long id=approve();assertEquals("PROCESSING",data.one("SELECT status FROM compute_withdrawal WHERE id=?",id).get("status"));verifyNoInteractions(client);
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));dispatchDue();
        assertEquals("PROCESSING",data.one("SELECT status FROM compute_withdrawal WHERE id=?",id).get("status"));
        var success=response("SUCCEEDED",3,false);success.put("tx_hash","hash_1");success.put("sender_address","source_address");
        assertEquals("SUCCEEDED",payouts.apply(success,null));assertEquals("SUCCEEDED",payouts.apply(success,null));
        assertEquals(0,balance("frozen").signum());assertEquals(0,new BigDecimal("900").compareTo(balance("available")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='WITHDRAW_APPROVED'",Integer.class));
        assertEquals("hash_1",jdbc.queryForObject("SELECT tx_hash FROM compute_withdrawal WHERE id=?",String.class,id));
    }

    @Test void notSubmittedTaskNeverMasqueradesAsARemoteOrderAndKeepsDiagnosticStage() {
        long id=approve();
        var rows=new ArrayList<>(List.of(data.one("SELECT * FROM compute_withdrawal WHERE id=?",id)));
        ComputePaymentRecords.enrich(data,"withdrawals",rows,true);
        assertNull(rows.get(0).get("gatewayReference"));assertEquals("NEW",rows.get(0).get("gatewayState"));
        assertEquals(0,number(rows.get(0).get("gatewayAttempts")));assertNotNull(rows.get(0).get("gatewayNextAttemptAt"));
        dispatchDue();
        rows=new ArrayList<>(List.of(data.one("SELECT * FROM compute_withdrawal WHERE id=?",id)));
        ComputePaymentRecords.enrich(data,"withdrawals",rows,true);
        assertEquals("po1",rows.get(0).get("gatewayReference"));assertEquals("SUBMITTED",rows.get(0).get("gatewayState"));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void trustedQuoteRejectionKeepsItsReasonAndNeverCreatesOrReleasesFunds() {
        approve();doThrow(new ComputeMcagRejectedException("CUSTODY_PAYOUT_DISABLED")).when(client).request(eq("payouts/quote"),anyMap());
        dispatchDue();
        var job=data.one("SELECT * FROM compute_mcag_payout");
        assertEquals("CUSTODY_PAYOUT_DISABLED",job.get("reason"));assertEquals("NEW",job.get("state"));
        assertEquals("NOT_SENT",job.get("submissionState"));assertNull(job.get("payoutId"));
        verify(client,never()).request(eq("payouts/create"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void insufficientFundsAutomaticallyRetriesOriginalOrderAndQuoteAfterFunding() {
        approve();
        when(client.request(eq("payouts/create"),anyMap()))
                .thenThrow(new ComputeMcagRejectedException("MERCHANT_POOL_UNAVAILABLE"))
                .thenThrow(new ComputeMcagRejectedException("MERCHANT_POOL_UNAVAILABLE"))
                .thenAnswer(invocation->{remote.set(response("QUEUED",1,false));return remote.get();});
        dispatchDue();
        var job=data.one("SELECT * FROM compute_mcag_payout");
        assertEquals("MERCHANT_POOL_UNAVAILABLE",job.get("reason"));assertEquals("RETRY_WAIT",job.get("state"));
        assertEquals("REJECTED",job.get("submissionState"));assertNull(job.get("payoutId"));assertEquals("q1",job.get("quoteId"));
        assertEquals("MCAG_QUEUED",jdbc.queryForObject("SELECT payment_status FROM compute_withdrawal",String.class));
        assertTrue(Instant.parse(text(job.get("nextAttemptAt"))).isAfter(Instant.now()));
        dispatchDue();assertEquals("RETRY_WAIT",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        dispatchDue();assertEquals("SUBMITTED",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        verify(client,times(3)).request(eq("payouts/create"),argThat(body->"q1".equals(body.get("quote_id")) && job.get("merchantOrderId").equals(body.get("merchant_order_id"))));
        verify(client,times(1)).request(eq("payouts/quote"),anyMap());
        assertEquals(job.get("merchantOrderId"),jdbc.queryForObject("SELECT merchant_order_id FROM compute_mcag_payout",String.class));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind IN ('WITHDRAW_APPROVED','WITHDRAW_RELEASE')",Integer.class));
        payouts.apply(response("SUCCEEDED",2,false),null);payouts.apply(response("SUCCEEDED",2,false),null);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='WITHDRAW_APPROVED'",Integer.class));
        assertEquals(0,balance("frozen").signum());
    }

    private void insufficientFunds(boolean legacy) {
        approve();doThrow(new ComputeMcagRejectedException("MERCHANT_POOL_UNAVAILABLE")).when(client).request(eq("payouts/create"),anyMap());
        dispatchDue();
        if (legacy) {
            jdbc.update("UPDATE compute_mcag_payout SET state='REVIEW'");
            jdbc.update("UPDATE compute_withdrawal SET payment_status='MCAG_REVIEW'");
        }
    }

    @Test void legacyInsufficientFundsRecoverySurvivesQueryFailureAndKeepsOriginalQuote() {
        insufficientFunds(true);
        doThrow(new BusinessException(504,"server.compute.payment.timeout")).when(client).request(eq("payouts/query"),anyMap());
        dispatchDue();
        assertEquals("RETRY_WAIT",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        assertEquals("GATEWAY_TIMEOUT",jdbc.queryForObject("SELECT reason FROM compute_mcag_payout",String.class));
        assertEquals("MCAG_QUEUED",jdbc.queryForObject("SELECT payment_status FROM compute_withdrawal",String.class));
        verify(client,times(1)).request(eq("payouts/create"),anyMap());
        doReturn(fields("items",List.of())).when(client).request(eq("payouts/query"),anyMap());
        doAnswer(invocation->response("QUEUED",1,false)).when(client).request(eq("payouts/create"),anyMap());
        dispatchDue();
        assertEquals("SUBMITTED",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        verify(client,times(2)).request(eq("payouts/create"),argThat(body->"q1".equals(body.get("quote_id"))));
        verify(client,times(1)).request(eq("payouts/quote"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void fundedRetryQueriesExistingRemoteOrderBeforeCreatingAgain() {
        insufficientFunds(true);remote.set(response("QUEUED",1,false));
        dispatchDue();
        assertEquals("SUBMITTED",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        verify(client,times(1)).request(eq("payouts/create"),anyMap());
        verify(client,times(1)).request(eq("payouts/quote"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void fundedRetryRefreshesExpiredQuoteOnlyAfterTrustedRejectionAndEmptyQuery() {
        insufficientFunds(false);
        var quote=data.jsonMap(jdbc.queryForObject("SELECT quote_json FROM compute_mcag_payout",String.class));
        quote.put("quote_id","q2");quote.put("expires_at",Instant.now().plusSeconds(120).toString());
        doReturn(quote).when(client).request(eq("payouts/quote"),anyMap());
        doThrow(new ComputeMcagRejectedException("QUOTE_EXPIRED_OR_USED")).when(client).request(eq("payouts/create"),anyMap());
        dispatchDue();
        assertEquals("NEW",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        assertNull(jdbc.queryForObject("SELECT quote_id FROM compute_mcag_payout",String.class));
        verify(client,times(3)).request(eq("payouts/query"),anyMap());
        doAnswer(invocation->response("QUEUED",1,false)).when(client).request(eq("payouts/create"),anyMap());
        dispatchDue();
        assertEquals("SUBMITTED",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        assertEquals("q2",jdbc.queryForObject("SELECT quote_id FROM compute_mcag_payout",String.class));
        var order=jdbc.queryForObject("SELECT merchant_order_id FROM compute_mcag_payout",String.class);
        verify(client,times(3)).request(eq("payouts/create"),argThat(body->order.equals(body.get("merchant_order_id"))));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void otherTrustedCreateRejectionsStillRequireReview() {
        approve();doThrow(new ComputeMcagRejectedException("CUSTODY_PAYOUT_DISABLED")).when(client).request(eq("payouts/create"),anyMap());
        dispatchDue();dispatchDue();
        assertEquals("REVIEW",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        verify(client,times(1)).request(eq("payouts/create"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void acceptedReviewWithPoolReasonNeverRetriesCreation() {
        approve();dispatchDue();var review=response("REVIEW",2,false);review.put("reason_code","MERCHANT_POOL_UNAVAILABLE");
        payouts.apply(review,null);remote.set(null);
        dispatchDue();
        assertEquals("REVIEW",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        assertEquals("REMOTE_ORDER_MISSING",jdbc.queryForObject("SELECT reason FROM compute_mcag_payout",String.class));
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT submission_state FROM compute_mcag_payout",String.class));
        verify(client,times(1)).request(eq("payouts/create"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void concurrentWorkersDoNotDuplicateFundedRetry() throws Exception {
        insufficientFunds(true);due();
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();assertTrue(finish.await(10,TimeUnit.SECONDS));remote.set(response("QUEUED",1,false));return remote.get();})
                .when(client).request(eq("payouts/create"),anyMap());
        var worker=Executors.newSingleThreadExecutor();
        try {
            var running=worker.submit(payouts::dispatch);
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            payouts.dispatch();
            verify(client,times(2)).request(eq("payouts/create"),anyMap());
            finish.countDown();running.get(10,TimeUnit.SECONDS);
        } finally {finish.countDown();worker.shutdownNow();}
        assertEquals("SUBMITTED",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void delayedPoolRejectionCannotOverwriteAcceptedOrder() {
        approve();
        doAnswer(invocation->{
            payouts.apply(response("QUEUED",1,false),null);
            throw new ComputeMcagRejectedException("MERCHANT_POOL_UNAVAILABLE");
        }).when(client).request(eq("payouts/create"),anyMap());
        dispatchDue();
        var job=data.one("SELECT * FROM compute_mcag_payout");
        assertEquals("SUBMITTED",job.get("state"));assertEquals("ACCEPTED",job.get("submissionState"));
        assertEquals("po1",job.get("payoutId"));assertEquals(1,number(job.get("resourceVersion")));
        assertEquals("MCAG_QUEUED",jdbc.queryForObject("SELECT payment_status FROM compute_withdrawal",String.class));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void delayedPoolRejectionCannotOverwriteNewLease() {
        approve();
        doAnswer(invocation->{
            jdbc.update("UPDATE compute_mcag_payout SET lease_token='new-worker',lease_until=?",now().plusMinutes(1));
            throw new ComputeMcagRejectedException("MERCHANT_POOL_UNAVAILABLE");
        }).when(client).request(eq("payouts/create"),anyMap());
        dispatchDue();dispatchDue();
        var job=data.one("SELECT * FROM compute_mcag_payout");
        assertEquals("CREATING",job.get("state"));assertEquals("IN_FLIGHT",job.get("submissionState"));
        assertEquals("new-worker",job.get("leaseToken"));assertEquals("q1",job.get("quoteId"));
        verify(client,times(1)).request(eq("payouts/create"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void legacyPoolReasonCannotRetryWithdrawalWithChainFactsOrLocalCompletion() {
        insufficientFunds(true);
        jdbc.update("UPDATE compute_withdrawal SET tx_hash='existing-hash'");
        dispatchDue();assertEquals("REVIEW",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        jdbc.update("UPDATE compute_withdrawal SET tx_hash=NULL,status='APPROVED',completion_mode='MCAG_COMPLETED'");
        dispatchDue();assertEquals("REVIEW",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        verify(client,times(1)).request(eq("payouts/create"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void expiredQuoteRejectionStillFollowsOriginalOrderFoundOnSecondQuery() {
        insufficientFunds(false);
        var accepted=response("QUEUED",1,false);
        doReturn(fields("items",List.of()),fields("items",List.of(accepted))).when(client).request(eq("payouts/query"),anyMap());
        doThrow(new ComputeMcagRejectedException("QUOTE_EXPIRED_OR_USED")).when(client).request(eq("payouts/create"),anyMap());
        dispatchDue();
        assertEquals("SUBMITTED",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        assertEquals("q1",jdbc.queryForObject("SELECT quote_id FROM compute_mcag_payout",String.class));
        verify(client,times(1)).request(eq("payouts/quote"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void transportFailuresAreClassifiedWithoutTrustingResponseTextOrReleasingPrincipal() {
        approve();
        for(var item:Map.of("timeout","GATEWAY_TIMEOUT","accessDenied","GATEWAY_ACCESS_DENIED","rateLimited","GATEWAY_RATE_LIMITED","invalidResponse","GATEWAY_INVALID_RESPONSE").entrySet()) {
            doThrow(new BusinessException(502,"server.compute.payment."+item.getKey())).when(client).request(eq("payouts/query"),anyMap());
            dispatchDue();assertEquals(item.getValue(),jdbc.queryForObject("SELECT reason FROM compute_mcag_payout",String.class));
            assertEquals("NOT_SENT",jdbc.queryForObject("SELECT submission_state FROM compute_mcag_payout",String.class));
        }
        doThrow(new BusinessException(502,"https://private.invalid/raw-error")).when(client).request(eq("payouts/query"),anyMap());
        dispatchDue();assertEquals("GATEWAY_UNAVAILABLE",jdbc.queryForObject("SELECT reason FROM compute_mcag_payout",String.class));
        doThrow(new ComputeMcagRejectedException("https://private.invalid/raw-error")).when(client).request(eq("payouts/query"),anyMap());
        dispatchDue();assertEquals("GATEWAY_INVALID_RESPONSE",jdbc.queryForObject("SELECT reason FROM compute_mcag_payout",String.class));
        verify(client,never()).request(eq("payouts/create"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }

    @Test void persistedGatewaySnapshotCannotBecomeManualApprovalWhenGatewayIsDisabled() {
        long id=pending(1,"97");
        var mappings=new ComputeMcagChannelService(data,config);
        jdbc.update("UPDATE compute_withdrawal SET gateway_snapshot=? WHERE id=?",data.json(mappings.snapshot(mappings.requireChannel("USDT","TRC20"))),id);
        config.setEnabled(false);
        var failure=assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->business.reviewWithdrawal(id,true,"批准",1)));
        assertEquals("server.compute.gateway.notConfigured",failure.getMessage());
        assertEquals("PENDING",data.one("SELECT status FROM compute_withdrawal WHERE id=?",id).get("status"));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
        verifyNoInteractions(client);
        config.setEnabled(true);
        tx.executeWithoutResult(status->business.reviewWithdrawal(id,true,"批准",1));
        assertEquals("PROCESSING",data.one("SELECT status FROM compute_withdrawal WHERE id=?",id).get("status"));
        assertEquals("USDT_TRON",data.one("SELECT asset_id FROM compute_mcag_payout WHERE withdrawal_id=?",id).get("assetId"));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
        verifyNoInteractions(client);
    }

    @Test void legacyNullSnapshotCannotBeApprovedBeforeFirstChannelSync() {
        long id=pending(1,"97");
        assertNull(data.one("SELECT gateway_snapshot FROM compute_withdrawal WHERE id=?",id).get("gatewaySnapshot"));
        jdbc.update("DELETE FROM compute_mcag_channel");
        var failure=assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->business.reviewWithdrawal(id,true,"批准",1)));
        assertEquals("server.compute.v15.channelNotSynced",failure.getMessage());
        assertEquals("PENDING",data.one("SELECT status FROM compute_withdrawal WHERE id=?",id).get("status"));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
        assertEquals(0,new BigDecimal("900").compareTo(balance("available")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));
        verifyNoInteractions(client);
    }
    @Test void unknownAndUnsafeFailureKeepFrozenUntilSafeTerminalFailure() {
        approve();dispatchDue();assertEquals("REVIEW",payouts.apply(response("UNKNOWN",2,false),null));
        assertEquals("REVIEW",payouts.apply(response("FAILED",3,false),null));assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
        var failure=response("FAILED",4,true);assertEquals("RELEASED",payouts.apply(failure,null));assertEquals("RELEASED",payouts.apply(failure,null));
        assertEquals(0,balance("frozen").signum());assertEquals(0,new BigDecimal("1000").compareTo(balance("available")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='WITHDRAW_RELEASE'",Integer.class));
        assertEquals("STALE",payouts.apply(response("UNKNOWN",2,false),null));
    }
    @Test void creationTimeoutRecoversOriginalMerchantOrderWithoutAnotherCreate() {
        approve();doAnswer(invocation->{remote.set(response("QUEUED",1,false));throw new BusinessException(504,"server.compute.payment.timeout");}).when(client).request(eq("payouts/create"),anyMap());
        dispatchDue();assertEquals("CREATING",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        dispatchDue();assertEquals("SUBMITTED",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        verify(client,times(1)).request(eq("payouts/create"),anyMap());verify(client,times(1)).request(eq("payouts/quote"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }
    @Test void unknownCreateRetriesSamePersistedQuoteAndOrder() {
        approve();doThrow(new BusinessException(504,"server.compute.payment.timeout")).when(client).request(eq("payouts/create"),anyMap());
        dispatchDue();String quote=jdbc.queryForObject("SELECT quote_id FROM compute_mcag_payout",String.class);
        dispatchDue();verify(client,times(2)).request(eq("payouts/create"),argThat(body->quote.equals(body.get("quote_id"))));
        verify(client,times(1)).request(eq("payouts/quote"),anyMap());
    }
    @Test void exactAmountAndRecipientMismatchesNeverSettle() {
        approve();dispatchDue();
        for(String field:List.of("merchant_order_id","customer_id","asset_id","network_id","destination_address","quote_id","payout_id","amount")) {
            var bad=response("SUCCEEDED",3,false);bad.put(field,field.equals("amount") ? "98" : "wrong");
            if (field.equals("merchant_order_id")) assertEquals("IGNORED",payouts.apply(bad,null));
            else assertThrows(BusinessException.class,()->payouts.apply(bad,null),field);
        }
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='WITHDRAW_APPROVED'",Integer.class));
    }
    @Test void unsupportedPrecisionNeverRoundsOrCreatesPayout() {
        long id=pending(1,"97.00000001");tx.executeWithoutResult(status->business.reviewWithdrawal(id,true,"批准",1));dispatchDue();
        assertEquals("AMOUNT_PRECISION",jdbc.queryForObject("SELECT reason FROM compute_mcag_payout",String.class));
        verify(client,never()).request(eq("payouts/create"),anyMap());verify(client,never()).request(eq("payouts/quote"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }
    @Test void testMemberUsesExistingLocalCompletionWithoutGatewayCalls() {
        long id=pending(2,"97");var done=tx.execute(status->business.reviewWithdrawal(id,true,"测试",1));
        assertEquals("TEST_COMPLETED",done.get("completionMode"));assertEquals("APPROVED",done.get("status"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_finance_snapshot WHERE business_type='WITHDRAWAL'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));payouts.dispatch();verifyNoInteractions(client);
    }
    @Test void concurrentFinalCallbacksProduceOneLedgerPosting() throws Exception {
        approve();dispatchDue();var success=response("SUCCEEDED",3,false);var pool=Executors.newFixedThreadPool(4);
        try {List<Future<String>> results=new ArrayList<>();for(int i=0;i<8;i++)results.add(pool.submit(()->payouts.apply(success,null)));
            for(var result:results)assertEquals("SUCCEEDED",result.get(20,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='WITHDRAW_APPROVED'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_finance_snapshot WHERE business_type='WITHDRAWAL'",Integer.class));
        assertEquals(0,new BigDecimal("97").compareTo(jdbc.queryForObject("SELECT usd_net_amount FROM compute_finance_snapshot",BigDecimal.class)));
    }
    @Test void failedLedgerWriteRollsBackRemoteAndLocalFinalState() {
        approve();dispatchDue();jdbc.execute("ALTER TABLE compute_ledger ADD CONSTRAINT reject_final CHECK(kind<>'WITHDRAW_APPROVED')");
        assertThrows(RuntimeException.class,()->payouts.apply(response("SUCCEEDED",3,false),null));
        assertEquals("SUBMITTED",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_finance_snapshot",Integer.class));
        assertEquals("PROCESSING",jdbc.queryForObject("SELECT status FROM compute_withdrawal",String.class));assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }
    @Test void unsubmittedReviewCanBeCancelledButUnknownCreationCannot() {
        long id=pending(1,"97.00000001");tx.executeWithoutResult(status->business.reviewWithdrawal(id,true,"批准",1));dispatchDue();
        String order=jdbc.queryForObject("SELECT merchant_order_id FROM compute_mcag_payout",String.class);
        assertEquals("RELEASED",payouts.cancelUnsubmitted(order,1,"精度不符撤销").get("state"));
        assertEquals(0,balance("frozen").signum());assertEquals(0,new BigDecimal("1000").compareTo(balance("available")));
        assertThrows(BusinessException.class,()->payouts.cancelUnsubmitted(order,1,"重复取消"));
    }
    @Test void inFlightWithoutRemoteResultCannotBeCancelledOrGetNewQuote() {
        approve();doThrow(new BusinessException(504,"server.compute.payment.timeout")).when(client).request(eq("payouts/create"),anyMap());dispatchDue();
        String order=jdbc.queryForObject("SELECT merchant_order_id FROM compute_mcag_payout",String.class);
        assertThrows(BusinessException.class,()->payouts.cancelUnsubmitted(order,1,"不能确定结果"));payouts.reconcile(order);
        assertEquals("q1",jdbc.queryForObject("SELECT quote_id FROM compute_mcag_payout",String.class));
        assertEquals("IN_FLIGHT",jdbc.queryForObject("SELECT submission_state FROM compute_mcag_payout",String.class));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }
    @Test void userQuoteRejectsChannelPrecisionBeforeAnyFreeze() {
        jdbc.update("UPDATE compute_currency_asset SET amount_scale=6 WHERE asset_code='USDT'");
        jdbc.update("UPDATE compute_currency SET fee_mode='CHANNEL',withdraw_fee_fixed=0,withdraw_fee_rate=0 WHERE currency='USDT'");
        BusinessException failure=assertThrows(BusinessException.class,()->business.quote(1,fields("currency","USDT","network","TRC20","amount","100.00000001")));
        assertEquals("server.compute.mcag.payoutPrecision",failure.getMessage());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_withdrawal",Integer.class));verifyNoInteractions(client);
    }
    @Test void duplicateCreateResponseKeepsQueriedChainCostsAndDetails() {
        approve();dispatchDue();var complete=response("SUCCEEDED",3,false);
        var pendingDetails=data.jsonMap(jdbc.queryForObject("SELECT chain_details_json FROM compute_mcag_payout",String.class));
        assertFalse(pendingDetails.containsKey("currentConfirmations"),"尚未取得实际确认数时不能生成默认值");
        complete.putAll(fields("tx_hash","hash1","sender_address","source1","chain_status","CONFIRMED","custody_status","SUCCEEDED",
                "actual_fee_native","0.000000000000000001234567890123","network_fee_asset_id","TRX_TRON"));
        complete.putAll(fields("current_confirmations",19,"required_confirmations",19,"finality_satisfied",true,
                "valuation_currency","USDT","valuation_usdt_amount","97","valuation_rate","1","review_reason","SINGLE_LIMIT_REVIEW"));
        payouts.apply(complete,null);payouts.apply(response("SUCCEEDED",3,false),null);
        var details=data.jsonMap(jdbc.queryForObject("SELECT chain_details_json FROM compute_mcag_payout",String.class));
        assertEquals("0.000000000000000001234567890123",details.get("actualFeeNative"));
        assertEquals("TRX_TRON",details.get("networkFeeAssetId"));assertEquals("SUCCEEDED",details.get("custodyStatus"));
        assertEquals("19",details.get("requiredConfirmations"));assertEquals(true,details.get("finalitySatisfied"));
        assertEquals("97",details.get("valuationUsdtAmount"));assertEquals("SINGLE_LIMIT_REVIEW",details.get("reviewReason"));
        assertEquals(19,jdbc.queryForObject("SELECT chain_confirmations FROM compute_withdrawal",Integer.class));
        assertEquals("CONFIRMED",details.get("chainStatus"));
        assertEquals(19L,number(details.get("currentConfirmations")),"缺少链详情的重复创建响应不能清除已查询的实际确认数");
        assertEquals("hash1",jdbc.queryForObject("SELECT tx_hash FROM compute_withdrawal",String.class));
        assertEquals(0,new BigDecimal("900").compareTo(balance("available")));
    }

    @Test void gatewayReviewKeepsFrozenUntilApprovedAndFinallyPaid() {
        approve();dispatchDue();
        assertEquals("REVIEW",payouts.apply(response("REVIEW",2,false),null));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
        assertEquals("MCAG_REVIEW",jdbc.queryForObject("SELECT payment_status FROM compute_withdrawal",String.class));
        assertEquals("SUBMITTED",payouts.apply(response("QUEUED",3,false),null));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
        assertEquals("SUCCEEDED",payouts.apply(response("SUCCEEDED",4,false),null));
        assertEquals(0,balance("frozen").signum());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='WITHDRAW_APPROVED'",Integer.class));
    }

    @Test void futureTaskDoesNotSendOrReleaseUntilDue() {
        approve();
        jdbc.update("UPDATE compute_mcag_payout SET next_attempt_at=?",now().plusMinutes(1));
        payouts.dispatch();
        verifyNoInteractions(client);
        assertEquals("NEW",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        assertNull(jdbc.queryForObject("SELECT quote_id FROM compute_mcag_payout",String.class));
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
        dispatchDue();
        assertEquals("SUBMITTED",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        verify(client,times(1)).request(eq("payouts/create"),anyMap());
        assertEquals(0,new BigDecimal("100").compareTo(balance("frozen")));
    }
}
