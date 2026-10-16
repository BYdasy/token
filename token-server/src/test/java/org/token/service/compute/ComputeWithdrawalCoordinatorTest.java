package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.model.security.JwtUserPrincipal;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实服务与 Spring 事务代理，只使用内存 H2 和模拟 Crypto 归属响应。 */
@Tag("regression")
class ComputeWithdrawalCoordinatorTest {
    private static final String ADDRESS="T"+"1".repeat(33), OTHER="T"+"2".repeat(33), PASSWORD="Pay123456";
    private JdbcTemplate jdbc;
    private FixtureComputeData data;
    private DataSourceTransactionManager transactions;
    private TransactionTemplate tx;
    private ComputeBusinessService business;
    private ComputeManagementService management;
    private ComputeWithdrawalCoordinator coordinator;
    private ComputeMcagClient client;
    private ComputeWithdrawalAddressGuard guard;
    private ComputeSystemMessageService messages;

    @BeforeEach void setup() throws Exception {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:withdrawal_coord_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000");
        jdbc=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);tx=new TransactionTemplate(transactions);
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run", "compute_member_group", "compute_mcag_payout");





        TokenTestSchema.apply(jdbc);ComputeMcagTestSchema.apply(jdbc);
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('summaryCurrency','USDT')");
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1)");
        TokenTestSchema.seedCurrencies(jdbc);
        jdbc.update("UPDATE compute_currency SET fee_mode='CHANNEL',withdraw_fee_fixed=1,withdraw_fee_rate=0");
        ComputeMcagTestSchema.seedMapping(jdbc,"USDT","TRC20","USDT_TRON","TRON",6);
        jdbc.update("INSERT INTO compute_level(id,name,threshold,enabled,auto_provider_ids) VALUES(1,'基础',0,1,'[]')");
        jdbc.update("INSERT INTO compute_member(id,username,password_hash,pay_password_hash,invite_code,level,enabled,created_at,updated_at) VALUES(1,'member1','unused',?,'invite1',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'member2','unused',?,'invite2',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                new PasswordUtils().hashPassword(PASSWORD),new PasswordUtils().hashPassword(PASSWORD));
        data=new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules());
        var config=new ComputeMcagProperties();config.setEnabled(true);config.setMerchantId("mch_token");
        var mappings=new ComputeMcagChannelService(data,config);
        client=mock(ComputeMcagClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<ComputeMcagClient> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.request(eq("customer-addresses/query"),anyMap())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"远端请求不能持有资金或地址事务");
            return Map.of("registered",false);
        });
        guard=spy(new ComputeWithdrawalAddressGuard(data,config,provider,mappings));
        messages=mock(ComputeSystemMessageService.class);
        business=proxy(new ComputeBusinessService(data,new ComputeCurrencyService(data,config,mappings),new PasswordUtils(),
                messages,null,null,config,new ComputeFinanceSnapshotService(data),mappings,guard));
        management=proxy(new ComputeManagementService(data,jdbc,new ComputeAgentScope(jdbc),new ComputeCatalogService(data,new PasswordUtils()),business));
        coordinator=new ComputeWithdrawalCoordinator(business,management,guard);
        business.initializeMember(1);business.initializeMember(2);
        jdbc.update("UPDATE compute_wallet SET available=100 WHERE wallet_type='EARNINGS' AND currency='USDT'");
        loginSuper();
    }

    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    private void loginSuper() {
        var principal=JwtUserPrincipal.builder().uid(1).username("admin").state(1).authorities(Set.of("super")).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of(new SimpleGrantedAuthority("super"))));
    }
    private Map<String,Object> addressRequest(){return fields("currency","USDT","network","TRC20","address",ADDRESS,"label","收款地址","payPassword",PASSWORD);}

    @Test void directApprovalConsumesOnlyOriginalFrozenAmountAndReplaysWithoutGatewayOrDuplicateMessage() {
        var order=coordinator.withdraw(1,withdrawalRequest("direct-approve-01"));
        long id=number(order.get("id"));
        clearInvocations(client, messages);
        jdbc.update("UPDATE compute_currency SET withdraw_fee_fixed=5");
        var done=management.directApproveWithdrawal(id,"线下已处理",1);
        var replay=management.directApproveWithdrawal(id,"重复确认",1);
        assertEquals("APPROVED",done.get("status"));
        assertEquals("DIRECT_CONFIRMED",done.get("completionMode"));
        assertEquals("DIRECT_CONFIRMED",done.get("paymentStatus"));
        assertEquals("POSTED",done.get("accountingStatus"));
        assertEquals("线下已处理",replay.get("remark"));
        assertEquals(0,new BigDecimal("80").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS'",BigDecimal.class)));
        assertEquals(0,jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS'",BigDecimal.class).signum());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE event_key=?",Integer.class,"WITHDRAW:"+id+":DIRECT"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_finance_snapshot WHERE business_type='WITHDRAWAL'",Integer.class));
        verify(messages,times(1)).withdrawalCompleted(eq(id),eq(1L),argThat(value->value.compareTo(new BigDecimal("19"))==0),eq("USDT"));
        verifyNoInteractions(client);
    }

    @Test void directApprovalRejectsQueuedPaymentEvenIfLocalStatusWasIncorrectlyResetToPending() {
        var order=coordinator.withdraw(1,withdrawalRequest("direct-block-queue"));
        long id=number(order.get("id"));
        business.reviewWithdrawal(id,true,"进入代付",1);
        assertThrows(BusinessException.class,()->management.directApproveWithdrawal(id,"直接处理",1));
        jdbc.update("UPDATE compute_withdrawal SET status='PENDING',completion_mode=NULL,payment_status='MANUAL_PENDING',chain_status='NOT_APPLICABLE' WHERE id=?",id);
        assertEquals("server.compute.v24Finance.directUnavailable",assertThrows(BusinessException.class,()->management.directApproveWithdrawal(id,"直接处理",1)).getMessage());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='WITHDRAW_APPROVED'",Integer.class));
        verify(messages,never()).withdrawalCompleted(anyLong(),anyLong(),any(),anyString());
    }

    @Test void directApprovalRejectsBroadcastFactsMissingOriginalFreezeAndEmptyAuditReason() {
        var order=coordinator.withdraw(1,withdrawalRequest("direct-block-facts"));
        long id=number(order.get("id"));
        jdbc.update("UPDATE compute_withdrawal SET tx_hash='broadcast-hash' WHERE id=?",id);
        assertEquals("server.compute.v24Finance.directUnavailable",assertThrows(BusinessException.class,()->business.directApproveWithdrawal(id,"处理",1)).getMessage());
        jdbc.update("UPDATE compute_withdrawal SET tx_hash=NULL WHERE id=?",id);
        assertEquals("server.compute.v24Finance.directRemarkRequired",assertThrows(BusinessException.class,()->business.directApproveWithdrawal(id," ",1)).getMessage());
        jdbc.update("UPDATE compute_ledger SET frozen_change=19 WHERE event_key=?","WITHDRAW:"+id+":FREEZE");
        assertEquals("server.compute.requestConflict",assertThrows(BusinessException.class,()->business.directApproveWithdrawal(id,"处理",1)).getMessage());
        assertEquals("PENDING",data.one("SELECT status FROM compute_withdrawal WHERE id=?",id).get("status"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='WITHDRAW_APPROVED'",Integer.class));
    }

    @Test void directApprovalConcurrentRetriesSettleOnlyOnce() throws Exception {
        var order=coordinator.withdraw(1,withdrawalRequest("direct-concurrent"));
        long id=number(order.get("id"));
        var start=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->{start.await();return business.directApproveWithdrawal(id,"确认",1);});
            var second=workers.submit(()->{start.await();return business.directApproveWithdrawal(id,"确认",1);});
            start.countDown();
            assertEquals("APPROVED",first.get(5,TimeUnit.SECONDS).get("status"));
            assertEquals("APPROVED",second.get(5,TimeUnit.SECONDS).get("status"));
        } finally {workers.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE event_key=?",Integer.class,"WITHDRAW:"+id+":DIRECT"));
        verify(messages,times(1)).withdrawalCompleted(eq(id),eq(1L),any(),eq("USDT"));
    }
    private Map<String,Object> withdrawalRequest(String requestId) {
        return withdrawalRequest(requestId,"USDT");
    }
    private Map<String,Object> withdrawalRequest(String requestId,String currency) {
        var request=fields("currency",currency,"network","TRC20","address",ADDRESS,"addressId",boundAddress(currency,"TRC20",ADDRESS),"amount","20","requestId",requestId,"payPassword",PASSWORD);
        var quote=business.quote(1,request);
        request.putAll(fields("expectedFee",quote.get("fee"),"expectedNetAmount",quote.get("netAmount"),
                "expectedAccountingRate",quote.get("accountingRate"),"expectedAccountingAmount",quote.get("accountingAmount")));
        return request;
    }
    private long boundAddress(String currency,String network,String address) {
        var existing=jdbc.queryForList("SELECT id FROM compute_address WHERE member_id=1 AND currency=? AND network=? AND address=? ORDER BY id LIMIT 1",Long.class,currency,network,address);
        if(!existing.isEmpty())return existing.get(0);
        return data.insert("compute_address",fields("member_id",1,"currency",currency,"network",network,"address",address,"label","已绑定地址","created_at",now()));
    }
    private void trxChannel() {
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled,fee_mode,withdraw_fee_fixed,withdraw_fee_rate) VALUES('TRX','TRC20',1,'CHANNEL',1,0)");
        TokenTestSchema.seedCurrencies(jdbc);
        ComputeMcagTestSchema.seedMapping(jdbc,"TRX","TRC20","TRX_TRON","TRON",6);
        jdbc.update("INSERT INTO compute_exchange_rate(rate_type,currency,rate,provider,source_id,source_at,refreshed_at) VALUES('ASSET','TRX',0.1,'MCAG','TRX',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }
    private void assertUsdWallet(String available,String frozen) {
        var wallet=data.one("SELECT available,frozen FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS' AND currency='USDT'");
        assertEquals(0,new BigDecimal(available).compareTo(decimal(wallet.get("available"))));
        assertEquals(0,new BigDecimal(frozen).compareTo(decimal(wallet.get("frozen"))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet WHERE member_id=1 AND currency='TRX'",Integer.class));
    }
    private void assertNoWithdrawal() {
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_withdrawal",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_operation",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
        assertEquals(0,decimal(jdbc.queryForObject("SELECT SUM(frozen) FROM compute_wallet",BigDecimal.class)).signum());
    }

    @Test void userWithdrawalQueriesBeforeLocksAndReplayDoesNotQueryAgain() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {
            when(client.request(eq("customer-addresses/query"),anyMap())).thenAnswer(call->{
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address_guard",Integer.class));
                executor.submit(()->tx.executeWithoutResult(status->jdbc.update("UPDATE compute_member SET username=username WHERE id=1"))).get(3,TimeUnit.SECONDS);
                return Map.of("registered",false);
            });
            var request=withdrawalRequest("withdraw-before-lock");var first=coordinator.withdraw(1,request);
            assertEquals(number(first.get("id")),number(coordinator.withdraw(1,request).get("id")));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_withdrawal",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
            assertEquals(0,new BigDecimal("20").compareTo(jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS'",BigDecimal.class)));
            verify(client,times(1)).request(eq("customer-addresses/query"),anyMap());
        } finally {executor.shutdownNow();}
    }

    @Test void belowReviewThresholdQueuesOnceWithFrozenFundsAndNoPaymentHttp() {
        new ComputeWithdrawalReviewPolicy(data).save(fields("mode","THRESHOLD","thresholdToken","21"));
        var request=withdrawalRequest("automatic-low-withdrawal");
        var first=coordinator.withdraw(1,request);
        assertEquals("PROCESSING",first.get("status"));
        assertNull(first.get("reviewedBy"));
        assertEquals(number(first.get("id")),number(coordinator.withdraw(1,request).get("id")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));
        assertEquals("NEW",jdbc.queryForObject("SELECT state FROM compute_mcag_payout",String.class));
        assertEquals(0,new BigDecimal("20").compareTo(jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS'",BigDecimal.class)));
        assertEquals(false,data.jsonMap(data.json(data.jsonMap(first.get("ruleSnapshot")).get("localReview"))).get("required"));
        verify(client,times(1)).request(eq("customer-addresses/query"),anyMap());
        verifyNoMoreInteractions(client);
    }

    @Test void reachingGrossThresholdWaitsLocallyEvenWhenNetAmountIsLower() {
        new ComputeWithdrawalReviewPolicy(data).save(fields("mode","THRESHOLD","thresholdToken","20"));
        var first=coordinator.withdraw(1,withdrawalRequest("manual-high-withdrawal"));
        assertEquals("PENDING",first.get("status"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));
        assertEquals(0,new BigDecimal("19").compareTo(decimal(first.get("netAmount"))));
    }

    @Test void originalCoinWithdrawalFreezesUsdAndSendsOriginalNetAmount() {
        trxChannel();
        var balance=business.withdrawalBalance(1,fields("currency","TRX","network","TRC20"));
        assertEquals(0,new BigDecimal("1000").compareTo(decimal(balance.get("availableAmount"))));
        new ComputeWithdrawalReviewPolicy(data).save(fields("mode","THRESHOLD","thresholdToken","3"));
        var order=coordinator.withdraw(1,withdrawalRequest("trx-success","TRX"));
        assertEquals("PROCESSING",order.get("status"));
        assertEquals("USD",order.get("accountingCurrency"));
        assertEquals("TRX",order.get("currency"));
        assertEquals(0,new BigDecimal("2").compareTo(decimal(order.get("accountingAmount"))));
        assertEquals(0,new BigDecimal("19").compareTo(new BigDecimal(jdbc.queryForObject("SELECT amount_text FROM compute_mcag_payout",String.class))));
        assertUsdWallet("98","2");
        jdbc.update("UPDATE compute_exchange_rate SET rate=0.9 WHERE currency='TRX'");
        tx.executeWithoutResult(status->business.completeMcagWithdrawal(number(order.get("id")),true));
        tx.executeWithoutResult(status->business.completeMcagWithdrawal(number(order.get("id")),true));
        assertUsdWallet("98","0");
        assertEquals("POSTED",data.one("SELECT accounting_status FROM compute_withdrawal WHERE id=?",order.get("id")).get("accountingStatus"));
        assertEquals(0,new BigDecimal("2").compareTo(jdbc.queryForObject("SELECT usd_amount FROM compute_finance_snapshot WHERE business_type='WITHDRAWAL'",BigDecimal.class)));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }

    @ParameterizedTest
    @CsvSource({
            "ETH,ERC20,0.00114000,2663.8098061446135,3.03674318,SUCCESS",
            "ETH,ERC20,0.00220000,2663.8098061446135,5.86038158,FAILURE",
            "POL,POLYGON,18.80000000,0.10151537231799711,1.90848900,REJECT",
            "ETH,ERC20,0.00114000,2500.123456789012345678,2.85014075,SUCCESS",
            "POL,POLYGON,18.80000000,0.123456789012345678,2.32098764,SUCCESS"
    })
    void decimalRateSnapshotPreservesNativeWithdrawalThroughReviewAndFinalReplay(String currency,String network,
            String nativeAmount,String rate,String frozenUsd,String outcome) {
        nativeChannel(currency,network,rate);
        var order=coordinator.withdraw(1,nativeWithdrawalRequest(currency,network,nativeAmount,"precise-rate-01"));
        long id=number(order.get("id"));
        String expectedRate=new BigDecimal(rate).setScale(18).toPlainString();
        assertEquals("PENDING",order.get("status"));
        assertEquals(expectedRate,data.jsonMap(order.get("accountingQuoteJson")).get("usdRate"));
        assertEquals(0,new BigDecimal(expectedRate).compareTo(decimal(order.get("accountingRate"))));
        assertEquals(0,new BigDecimal(frozenUsd).compareTo(decimal(order.get("accountingAmount"))));
        assertUsdWallet(new BigDecimal("100").subtract(new BigDecimal(frozenUsd)).toPlainString(),frozenUsd);
        clearInvocations(client,messages);
        // 行情消失或刷新不能改变已经冻结的订单报价。
        jdbc.update("DELETE FROM compute_exchange_rate WHERE currency=?",currency);
        if("REJECT".equals(outcome)) {
            business.reviewWithdrawal(id,false,"拒绝",1);
            assertEquals("server.compute.alreadyReviewed",assertThrows(BusinessException.class,
                    ()->business.reviewWithdrawal(id,false,"重复拒绝",1)).getMessage());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));
        } else {
            var queued=business.reviewWithdrawal(id,true,"审核通过",1);
            assertEquals("PROCESSING",queued.get("status"));
            var job=data.one("SELECT * FROM compute_mcag_payout WHERE withdrawal_id=?",id);
            assertEquals(order.get("orderNo"),job.get("merchantOrderId"));
            assertEquals(0,new BigDecimal(nativeAmount).compareTo(decimal(job.get("amountText"))));
            assertEquals("NEW",job.get("state"));
            assertUsdWallet(new BigDecimal("100").subtract(new BigDecimal(frozenUsd)).toPlainString(),frozenUsd);
            boolean success="SUCCESS".equals(outcome);
            tx.executeWithoutResult(status->business.completeMcagWithdrawal(id,success));
            tx.executeWithoutResult(status->business.completeMcagWithdrawal(id,success));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));
        }
        boolean success="SUCCESS".equals(outcome);
        assertUsdWallet(success?new BigDecimal("100").subtract(new BigDecimal(frozenUsd)).toPlainString():"100","0");
        var done=data.one("SELECT status,accounting_status FROM compute_withdrawal WHERE id=?",id);
        assertEquals(success?"APPROVED":"REJECTED",done.get("status"));
        assertEquals(success?"POSTED":"RELEASED",done.get("accountingStatus"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
        assertEquals(success?1:0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_finance_snapshot WHERE business_type='WITHDRAWAL'",Integer.class));
        verify(messages,times(success?1:0)).withdrawalCompleted(eq(id),eq(1L),any(),eq(currency));
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({
            "ETH,ERC20,0.00114000,2663.8098061446135,2663.809806144614,3.03674318",
            "POL,POLYGON,18.80000000,0.10151537231799711,0.10151537231799712,1.90848900"
    })
    void previouslyRoundedNumericQuoteCannotSilentlyQueueOrReleaseFunds(String currency,String network,
            String nativeAmount,String rate,String roundedJsonRate,String frozenUsd) {
        nativeChannel(currency,network,rate);
        var order=coordinator.withdraw(1,nativeWithdrawalRequest(currency,network,nativeAmount,"rounded-rate-01"));
        long id=number(order.get("id"));
        var quote=new LinkedHashMap<>(data.jsonMap(order.get("accountingQuoteJson")));
        // H2 夹具以 VARCHAR 代替 MySQL JSON；注入已在 MySQL 常量转换中核实的旧舍入结果。
        quote.put("usdRate",new BigDecimal(roundedJsonRate));
        jdbc.update("UPDATE compute_withdrawal SET accounting_quote_json=? WHERE id=?",data.json(quote),id);
        assertEquals("server.compute.withdrawalRateSnapshotMismatch",assertThrows(BusinessException.class,
                ()->business.reviewWithdrawal(id,true,"通过",1)).getMessage());
        assertEquals("server.compute.withdrawalRateSnapshotMismatch",assertThrows(BusinessException.class,
                ()->business.reviewWithdrawal(id,false,"拒绝",1)).getMessage());
        assertEquals("PENDING",data.one("SELECT status FROM compute_withdrawal WHERE id=?",id).get("status"));
        assertUsdWallet(new BigDecimal("100").subtract(new BigDecimal(frozenUsd)).toPlainString(),frozenUsd);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }

    @Test void fixedUsdQuotesAlsoPreserveDecimalText() {
        for(String currency:List.of("USD","USDT","USDC","USDT0")) {
            var quote=new ComputeUsdAccounting(data).strictQuote(currency);
            assertEquals("1",quote.get("usdRate"));
            assertEquals("1",data.jsonMap(data.json(quote)).get("usdRate"));
        }
    }

    private void nativeChannel(String currency,String network,String rate) {
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled,fee_mode,withdraw_fee_fixed,withdraw_fee_rate) VALUES(?,?,1,'CHANNEL',0,0)",currency,network);
        TokenTestSchema.seedCurrencies(jdbc);
        jdbc.update("UPDATE compute_currency_network SET chain_type='EVM' WHERE network_code=?",network);
        ComputeMcagTestSchema.seedMapping(jdbc,currency,network,currency+"_NATIVE",network+"_MAINNET",18);
        jdbc.update("INSERT INTO compute_exchange_rate(rate_type,currency,rate,provider,source_id,source_at,refreshed_at) VALUES('ASSET',?,?,'MCAG',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",currency,new BigDecimal(rate),currency);
    }

    private Map<String,Object> nativeWithdrawalRequest(String currency,String network,String nativeAmount,String requestId) {
        String address="0x"+"3".repeat(40);
        var request=fields("currency",currency,"network",network,"address",address,"addressId",boundAddress(currency,network,address),"amount",nativeAmount,
                "requestId",requestId,"payPassword",PASSWORD);
        var quote=business.quote(1,request);
        request.putAll(fields("expectedFee",quote.get("fee"),"expectedNetAmount",quote.get("netAmount"),
                "expectedAccountingRate",quote.get("accountingRate"),"expectedAccountingAmount",quote.get("accountingAmount")));
        return request;
    }

    @Test void terminalFailureReturnsFrozenUsdWithoutUsingLatestRate() {
        trxChannel();
        new ComputeWithdrawalReviewPolicy(data).save(fields("mode","THRESHOLD","thresholdToken","3"));
        var order=coordinator.withdraw(1,withdrawalRequest("trx-release","TRX"));
        jdbc.update("DELETE FROM compute_exchange_rate WHERE currency='TRX'");
        tx.executeWithoutResult(status->business.completeMcagWithdrawal(number(order.get("id")),false));
        tx.executeWithoutResult(status->business.completeMcagWithdrawal(number(order.get("id")),false));
        assertUsdWallet("100","0");
        assertEquals("RELEASED",data.one("SELECT accounting_status FROM compute_withdrawal WHERE id=?",order.get("id")).get("accountingStatus"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }

    @Test void missingVersionTwoSnapshotCannotRefundOrQueuePayment() {
        trxChannel();
        var order=coordinator.withdraw(1,withdrawalRequest("trx-corrupt-snapshot","TRX"));
        jdbc.update("UPDATE compute_withdrawal SET accounting_amount=NULL WHERE id=?",order.get("id"));
        assertThrows(BusinessException.class,()->business.reviewWithdrawal(number(order.get("id")),false,"拒绝",1));
        assertThrows(BusinessException.class,()->business.reviewWithdrawal(number(order.get("id")),true,"通过",1));
        assertUsdWallet("98","2");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));
    }

    @Test void originalCoinRateChangeRequiresFreshQuoteBeforeFundsCanFreeze() {
        trxChannel();
        var request=withdrawalRequest("trx-quote-change","TRX");
        jdbc.update("UPDATE compute_exchange_rate SET rate=0.2 WHERE currency='TRX'");
        assertEquals("server.compute.quoteChanged",assertThrows(BusinessException.class,()->coordinator.withdraw(1,request)).getMessage());
        assertNoWithdrawal();
    }

    @Test void consistentAlteredSnapshotCannotConsumeAnotherOrdersFrozenFunds() {
        trxChannel();
        new ComputeWithdrawalLimitPolicy(data).save(fields("enabled",false,"period","DAY","count",1));
        var first=coordinator.withdraw(1,withdrawalRequest("trx-owned-freeze-first","TRX"));
        coordinator.withdraw(1,withdrawalRequest("trx-owned-freeze-second","TRX"));
        assertUsdWallet("96","4");
        var changedQuote=new LinkedHashMap<>(data.jsonMap(first.get("accountingQuoteJson")));
        changedQuote.put("usdRate","0.2");
        jdbc.update("UPDATE compute_withdrawal SET accounting_amount=4,accounting_rate=0.2,accounting_fee=0.2,accounting_net_amount=3.8,accounting_quote_json=? WHERE id=?",
                data.json(changedQuote),first.get("id"));
        long id=number(first.get("id"));
        assertEquals("server.compute.requestConflict",assertThrows(BusinessException.class,()->business.reviewWithdrawal(id,true,"审核",1)).getMessage());
        assertEquals("server.compute.requestConflict",assertThrows(BusinessException.class,()->business.reviewWithdrawal(id,false,"拒绝",1)).getMessage());
        jdbc.update("UPDATE compute_withdrawal SET status='PROCESSING',completion_mode='MCAG_PENDING' WHERE id=?",id);
        assertEquals("server.compute.requestConflict",assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->business.completeMcagWithdrawal(id,true))).getMessage());
        assertEquals("server.compute.requestConflict",assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->business.completeMcagWithdrawal(id,false))).getMessage());
        assertUsdWallet("96","4");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_payout",Integer.class));
    }

    @Test void manualWithdrawalAutomaticallyQuotesUsdAndReplayKeepsOriginalSnapshot() {
        trxChannel();
        var request=withdrawalRequest("trx-manual-replay","TRX");
        request.remove("payPassword");request.remove("expectedAccountingRate");request.remove("expectedAccountingAmount");
        request.putAll(fields("memberId",1,"remark","人工提款"));
        var order=coordinator.manualWithdrawal(request);
        jdbc.update("UPDATE compute_exchange_rate SET rate=0.7 WHERE currency='TRX'");
        assertEquals(number(order.get("id")),number(coordinator.manualWithdrawal(request).get("id")));
        assertUsdWallet("98","2");
        business.reviewWithdrawal(number(order.get("id")),false,"人工拒绝",1);
        assertUsdWallet("100","0");
    }

    @Test void legacyOriginalCoinWithdrawalReleasesOnlyItsOriginalWallet() {
        trxChannel();
        jdbc.update("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen,updated_at) VALUES(1,'EARNINGS','TRX',7,20,CURRENT_TIMESTAMP)");
        long id=data.insert("compute_withdrawal",fields("member_id",1,"currency","TRX","network","TRC20","amount",20,"fee",1,
                "net_amount",19,"address",ADDRESS,"rule_snapshot","{}","status","PROCESSING","completion_mode","MCAG_PENDING","created_at",now()));
        jdbc.update("DELETE FROM compute_exchange_rate WHERE currency='TRX'");
        tx.executeWithoutResult(status->business.completeMcagWithdrawal(id,false));
        var legacy=data.one("SELECT available,frozen FROM compute_wallet WHERE member_id=1 AND currency='TRX'");
        assertEquals(0,new BigDecimal("27").compareTo(decimal(legacy.get("available"))));
        assertEquals(0,decimal(legacy.get("frozen")).signum());
        assertEquals(0,new BigDecimal("100").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS' AND currency='USDT'",BigDecimal.class)));
        assertEquals("LEGACY",data.one("SELECT accounting_status FROM compute_withdrawal WHERE id=?",id).get("accountingStatus"));
    }

    @Test void wrongPasswordOrUnavailableRemoteDoesNotWriteRequestsAddressesOrFunds() {
        var request=withdrawalRequest("withdraw-remote-fail");request.put("payPassword","wrong");
        assertThrows(BusinessException.class,()->coordinator.withdraw(1,request));verifyNoInteractions(client);
        request.put("payPassword",PASSWORD);
        when(client.request(anyString(),anyMap())).thenThrow(new BusinessException(503,"server.compute.gateway.notConfigured"));
        assertThrows(BusinessException.class,()->coordinator.withdraw(1,request));
        assertThrows(BusinessException.class,()->coordinator.saveAddress(1,addressRequest()));
        assertNoWithdrawal();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address",Integer.class),"预先绑定的地址保留，远端失败不能新增地址");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address_guard",Integer.class));
    }

    @Test void missingOrNonPositiveIntegerAddressIdIsRejectedBeforeRemoteAndInsideFundsTransaction() {
        var request=withdrawalRequest("bound-address-required");
        // 请求中的管理员标识不能绕过用户入口的绑定要求。
        request.put("operator",1);request.put("recordSource","ADMIN_MANUAL");
        for(Object invalid:new Object[]{null,"",0,-1,"1.5",new BigDecimal("1.5"),true,List.of(1),"9223372036854775808"}) {
            if(invalid==null)request.remove("addressId");else request.put("addressId",invalid);
            assertEquals("server.compute.boundWithdrawalAddressRequired",assertThrows(BusinessException.class,()->coordinator.withdraw(1,request)).getMessage());
            assertEquals("server.compute.boundWithdrawalAddressRequired",assertThrows(BusinessException.class,()->business.withdraw(1,request)).getMessage());
            assertNoWithdrawal();
        }
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({"MISSING","OTHER_MEMBER","DISABLED","REMOVED","CURRENCY","NETWORK"})
    void unavailableBoundAddressIsRejectedBeforeRemote(String change) {
        var request=withdrawalRequest("bound-address-unavailable");
        mutateBoundAddress(number(request.get("addressId")),change);
        assertEquals("server.compute.notFound",assertThrows(BusinessException.class,()->coordinator.withdraw(1,request)).getMessage());
        verifyNoInteractions(client);assertNoWithdrawal();
    }

    @ParameterizedTest
    @CsvSource({"MISSING","OTHER_MEMBER","DISABLED","REMOVED","CURRENCY","NETWORK"})
    void boundAddressIsRevalidatedAfterRemoteBeforeAnyFreeze(String change) {
        var request=withdrawalRequest("bound-address-changed");
        when(client.request(eq("customer-addresses/query"),anyMap())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            mutateBoundAddress(number(request.get("addressId")),change);
            return Map.of("registered",false);
        });
        assertEquals("server.compute.notFound",assertThrows(BusinessException.class,()->coordinator.withdraw(1,request)).getMessage());
        verify(client,times(1)).request(eq("customer-addresses/query"),anyMap());
        assertNoWithdrawal();
    }

    private void mutateBoundAddress(long addressId,String change) {
        switch(change) {
            case "MISSING" -> jdbc.update("DELETE FROM compute_address WHERE id=?",addressId);
            case "OTHER_MEMBER" -> jdbc.update("UPDATE compute_address SET member_id=2 WHERE id=?",addressId);
            case "DISABLED" -> jdbc.update("UPDATE compute_address SET enabled=0 WHERE id=?",addressId);
            case "REMOVED" -> jdbc.update("UPDATE compute_address SET removed=1 WHERE id=?",addressId);
            case "CURRENCY" -> jdbc.update("UPDATE compute_address SET currency='USDC' WHERE id=?",addressId);
            case "NETWORK" -> jdbc.update("UPDATE compute_address SET network='ERC20' WHERE id=?",addressId);
            default -> throw new AssertionError(change);
        }
    }

    @Test void validBoundIdUsesStoredRecipientAndConflictingInlineRecipientCannotOverrideIt() {
        var request=withdrawalRequest("bound-address-snapshot");
        request.put("address",OTHER);
        assertEquals("server.compute.recipientChanged",assertThrows(BusinessException.class,()->coordinator.withdraw(1,request)).getMessage());
        verifyNoInteractions(client);assertNoWithdrawal();
        request.remove("address");
        var order=coordinator.withdraw(1,request);
        assertEquals(ADDRESS,order.get("address"));
        assertEquals(ADDRESS,data.jsonMap(order.get("addressSnapshot")).get("address"));
        assertSingleWithdrawalFreeze();
    }

    @Test void successfulLegacyInlineAddressReplaySurvivesRemovedBindingWithoutNewRemoteOrFreeze() throws Exception {
        var request=withdrawalRequest("legacy-inline-address");
        var original=coordinator.withdraw(1,request);
        // 在隔离夹具中还原旧版本成功请求的原始摘要：其目的地已冻结为订单快照，但当时没有 addressId。
        request.remove("addressId");
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(data.json(new TreeMap<>(request)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("UPDATE compute_request SET payload_hash=? WHERE member_id=1 AND request_id=?",digest,request.get("requestId"));
        jdbc.update("DELETE FROM compute_address");
        clearInvocations(client);
        assertEquals(number(original.get("id")),number(coordinator.withdraw(1,request).get("id")));
        assertEquals(number(original.get("id")),number(business.withdraw(1,request).get("id")));
        verifyNoInteractions(client);assertSingleWithdrawalFreeze();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_request",Integer.class));
        var newRequest=new LinkedHashMap<>(request);newRequest.put("requestId","legacy-inline-new-attempt");
        assertEquals("server.compute.boundWithdrawalAddressRequired",assertThrows(BusinessException.class,()->coordinator.withdraw(1,newRequest)).getMessage());
        assertSingleWithdrawalFreeze();
    }

    @Test void changedSavedRecipientIsRecheckedAfterRemoteAndDoesNotFreeze() {
        var saved=coordinator.saveAddress(1,addressRequest());
        var request=withdrawalRequest("withdraw-address-race");request.remove("address");request.put("addressId",saved.get("id"));
        when(client.request(anyString(),anyMap())).thenAnswer(call->{jdbc.update("UPDATE compute_address SET address=? WHERE id=?",OTHER,saved.get("id"));return Map.of("registered",false);});
        assertEquals("server.compute.recipientChanged",assertThrows(BusinessException.class,()->coordinator.withdraw(1,request)).getMessage());
        assertNoWithdrawal();
    }

    @Test void mappingAndPaymentPasswordChangesFailClosed() {
        var request=withdrawalRequest("withdraw-config-race");
        when(client.request(anyString(),anyMap())).thenAnswer(call->{jdbc.update("UPDATE compute_mcag_channel SET mapping_version=mapping_version+1");return Map.of("registered",false);});
        assertEquals("server.compute.recipientChanged",assertThrows(BusinessException.class,()->coordinator.withdraw(1,request)).getMessage());
        assertNoWithdrawal();
        when(client.request(anyString(),anyMap())).thenAnswer(call->{jdbc.update("UPDATE compute_member SET pay_password_hash=? WHERE id=1",new PasswordUtils().hashPassword("Changed123"));return Map.of("registered",false);});
        assertEquals("server.compute.payPasswordInvalid",assertThrows(BusinessException.class,()->coordinator.withdraw(1,request)).getMessage());
        assertNoWithdrawal();
    }

    @Test void mappingChangedAfterAddressLockCannotReplaceTheVerifiedPayoutNetwork() {
        var request=withdrawalRequest("withdraw-late-mapping");
        doAnswer(call->{
            var key=call.callRealMethod();
            jdbc.update("UPDATE compute_mcag_channel SET network_id='OTHER_NETWORK',mapping_version=mapping_version+1");
            return key;
        }).when(guard).assertAllowed(anyLong(),anyMap(),any(ComputeCurrencyService.AddressData.class),any(ComputeWithdrawalAddressGuard.Proof.class));
        assertEquals("server.compute.recipientChanged",assertThrows(BusinessException.class,()->coordinator.withdraw(1,request)).getMessage());
        assertNoWithdrawal();
    }

    @Test void adminManualWithdrawalUsesRemoteBeforeTransactionAndReplaysWithoutRemote() {
        var request=withdrawalRequest("admin-withdraw-once");request.remove("payPassword");request.put("memberId",1);request.put("remark","手工提款核对");
        request.remove("addressId");jdbc.update("DELETE FROM compute_address");
        var first=coordinator.manualWithdrawal(request);
        assertEquals("ADMIN_MANUAL",first.get("recordSource"));
        assertEquals(number(first.get("id")),number(coordinator.manualWithdrawal(request).get("id")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_operation",Integer.class));
        verify(client,times(1)).request(eq("customer-addresses/query"),anyMap());
    }

    @Test void manualWithdrawalCannotBypassAnAcknowledgedTaskAndReplayRemainsIdempotent() {
        var request=withdrawalRequest("manual-task-restricted");
        request.remove("payPassword");request.put("memberId",1);request.put("remark","验证任务限制同样保护手工提款");
        long task=data.insert("compute_content",fields("kind","TASK","title","提款限制","body_html","<p>限制解除后才可提款</p>",
                "created_by",1,"status","PUBLISHED","task_mode",2,"target_type","USER","created_at",now(),"updated_at",now()));
        jdbc.update("INSERT INTO compute_content_recipient(content_id,member_id,created_at,completed_at) VALUES(?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",task);
        assertEquals("server.compute.v17.taskRestricted",assertThrows(BusinessException.class,()->coordinator.manualWithdrawal(request)).getMessage());
        assertNoWithdrawal();
        jdbc.update("UPDATE compute_content_recipient SET restriction_released_at=CURRENT_TIMESTAMP,restriction_released_by=1 WHERE content_id=? AND member_id=1",task);
        var accepted=coordinator.manualWithdrawal(request);
        assertSingleWithdrawalFreeze();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_operation",Integer.class));
        // 新增限制只能拦新提款，已经成功的相同请求仍原样返回，不再次冻结或发出远端查询。
        long laterTask=data.insert("compute_content",fields("kind","TASK","title","新提款限制","body_html","<p>后续提款受限</p>",
                "created_by",1,"status","PUBLISHED","task_mode",2,"target_type","USER","created_at",now(),"updated_at",now()));
        jdbc.update("INSERT INTO compute_content_recipient(content_id,member_id,created_at) VALUES(?,1,CURRENT_TIMESTAMP)",laterTask);
        assertEquals(number(accepted.get("id")),number(coordinator.manualWithdrawal(request).get("id")));
        verify(client,times(2)).request(eq("customer-addresses/query"),anyMap());
        var next=new LinkedHashMap<>(request);next.put("requestId","manual-task-restricted-next");
        assertEquals("server.compute.v17.taskRestricted",assertThrows(BusinessException.class,()->coordinator.manualWithdrawal(next)).getMessage());
        assertSingleWithdrawalFreeze();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_operation",Integer.class));
    }

    @Test void concurrentDifferentRequestsShareDefaultDailyQuotaAndFreezeOnlyOnce() throws Exception {
        var first=withdrawalRequest("daily-concurrent-first");
        var second=withdrawalRequest("daily-concurrent-second");
        var outcomes=concurrentWithdrawals(first,second);
        assertEquals(1,outcomes.stream().filter(Map.class::isInstance).count());
        var failures=outcomes.stream().filter(BusinessException.class::isInstance).map(BusinessException.class::cast).toList();
        assertEquals(1,failures.size());
        assertEquals("server.compute.v17.withdrawalLimitReached",failures.get(0).getMessage());
        assertSingleWithdrawalFreeze();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_request WHERE request_type='WITHDRAWAL'",Integer.class));
        assertThrows(BusinessException.class,()->business.quote(1,first));
    }

    @Test void concurrentSameRequestAndLaterReplayDoNotSpendAdditionalQuota() throws Exception {
        var request=withdrawalRequest("daily-concurrent-replay");
        var outcomes=concurrentWithdrawals(request,new LinkedHashMap<>(request));
        assertTrue(outcomes.stream().allMatch(Map.class::isInstance),"同一个请求必须返回同一提款而不是次数超限");
        long id=number(((Map<?,?>)outcomes.get(0)).get("id"));
        assertEquals(id,number(((Map<?,?>)outcomes.get(1)).get("id")));
        assertSingleWithdrawalFreeze();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_request WHERE request_type='WITHDRAWAL'",Integer.class));
        assertEquals(id,number(coordinator.withdraw(1,request).get("id")));
        verify(client,times(2)).request(eq("customer-addresses/query"),anyMap());
    }

    @Test void userWithdrawalAlsoOccupiesTheManualWithdrawalQuota() {
        assertUserAndManualShareDailyQuota(false);
    }

    @Test void manualWithdrawalAlsoOccupiesTheUserWithdrawalQuota() {
        assertUserAndManualShareDailyQuota(true);
    }

    private void assertUserAndManualShareDailyQuota(boolean manualFirst) {
        var user=withdrawalRequest("daily-user");
        var manual=withdrawalRequest("daily-manual");
        manual.remove("payPassword");manual.put("memberId",1);manual.put("remark","验证用户与手工提款共用次数");
        var first=manualFirst?coordinator.manualWithdrawal(manual):coordinator.withdraw(1,user);
        var failure=assertThrows(BusinessException.class,()->{
            if(manualFirst)coordinator.withdraw(1,user);else coordinator.manualWithdrawal(manual);
        });
        assertEquals("server.compute.v17.withdrawalLimitReached",failure.getMessage());
        var replay=manualFirst?coordinator.manualWithdrawal(manual):coordinator.withdraw(1,user);
        assertEquals(number(first.get("id")),number(replay.get("id")));
        assertSingleWithdrawalFreeze();
        assertEquals(manualFirst?1:0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_operation",Integer.class));
        assertEquals(manualFirst?0:1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_request WHERE request_type='WITHDRAWAL'",Integer.class));
    }

    private List<Object> concurrentWithdrawals(Map<String,Object> first,Map<String,Object> second) throws Exception {
        var ready=new CountDownLatch(2);var release=new CountDownLatch(1);
        when(client.request(eq("customer-addresses/query"),anyMap())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            ready.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));
            return Map.of("registered",false);
        });
        var executor=Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures=new ArrayList<>();
            for(var request:List.of(first,second))futures.add(executor.submit(()->{
                try{return coordinator.withdraw(1,request);}catch(BusinessException exception){return exception;}
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS),"两个请求都应在资金事务外完成远端归属查询");release.countDown();
            List<Object> result=new ArrayList<>();
            for(var future:futures)result.add(future.get(10,TimeUnit.SECONDS));
            return result;
        } finally {release.countDown();executor.shutdownNow();}
    }

    private void assertSingleWithdrawalFreeze() {
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_withdrawal",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind='WITHDRAW_FREEZE'",Integer.class));
        assertEquals(0,new BigDecimal("20").compareTo(jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS' AND currency='USDT'",BigDecimal.class)));
        assertEquals(0,new BigDecimal("80").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='EARNINGS' AND currency='USDT'",BigDecimal.class)));
    }

    @Test void adminAddressCreateAndEditPreserveUniqueOwnershipAndNeedOneRemoteCheckEach() {
        var request=addressRequest();request.put("memberId",1);
        var saved=coordinator.saveManagedAddress(null,request);
        verify(client,times(1)).request(eq("customer-addresses/query"),anyMap());
        var edited=coordinator.saveManagedAddress(number(saved.get("id")),Map.of("label","新标签"));
        assertEquals("新标签",edited.get("label"));
        assertEquals("server.compute.v15.addressAlreadyBound",assertThrows(BusinessException.class,()->coordinator.saveAddress(2,addressRequest())).getMessage());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address",Integer.class));
        coordinator.saveManagedAddress(number(saved.get("id")),Map.of("enabled",false));
        verify(client,times(3)).request(eq("customer-addresses/query"),anyMap());
    }

    private Map<String,Object> addressRequest(String address) {
        var request=addressRequest();request.put("address",address);return request;
    }

    private List<Long> activeAddressIds(long member) {
        // 与用户地址列表共用真实 Mapper 条件，不能只在测试中自行过滤 enabled/removed。
        var queries=new org.token.repository.compute.ComputeControllerQueryRepository(
                data.mapper(org.token.repository.dao.compute.ComputeControllerQueryMapper.class));
        return queries.memberAddresses(member).stream().map(row->number(row.get("id"))).toList();
    }

    @Test void disabledAddressReleasesCapacityAndAdminReactivationCannotOverfillIt() {
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('addressLimit','1')");
        long first=number(coordinator.saveAddress(1,addressRequest()).get("id"));
        coordinator.saveManagedAddress(first,Map.of("enabled",false));
        assertFalse(jdbc.queryForObject("SELECT enabled FROM compute_address WHERE id=?",Boolean.class,first));
        long second=number(coordinator.saveAddress(1,addressRequest(OTHER)).get("id"));
        assertEquals(List.of(second),activeAddressIds(1));
        assertEquals(2,((List<?>)management.addresses(Map.of("username","member1")).get("records")).size());
        assertEquals("server.compute.v6.addressLimitReached",assertThrows(BusinessException.class,
                ()->coordinator.saveAddress(1,addressRequest("T"+"3".repeat(33)))).getMessage());
        var original=data.one("SELECT * FROM compute_address WHERE id=?",first);
        assertEquals("server.compute.v6.addressLimitReached",assertThrows(BusinessException.class,
                ()->coordinator.saveManagedAddress(first,fields("enabled",true,"label","不应写入"))).getMessage());
        assertEquals(original,data.one("SELECT * FROM compute_address WHERE id=?",first));
        assertEquals(List.of(second),activeAddressIds(1));
        coordinator.saveManagedAddress(second,Map.of("enabled",false));
        coordinator.saveManagedAddress(first,Map.of("enabled",true));
        assertEquals(List.of(first),activeAddressIds(1));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address",Integer.class));
        assertNoWithdrawal();
    }

    @Test void personalCapacityOverridesGlobalAndZeroBlocksNewOrRestoredAddresses() {
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('addressLimit','1')");
        management.updateMember(1,Map.of("addressLimit",2));
        long first=number(coordinator.saveAddress(1,addressRequest()).get("id"));
        long second=number(coordinator.saveAddress(1,addressRequest(OTHER)).get("id"));
        assertEquals(2,activeAddressIds(1).size());
        assertEquals("server.compute.v6.addressLimitReached",assertThrows(BusinessException.class,
                ()->coordinator.saveAddress(1,addressRequest("T"+"3".repeat(33)))).getMessage());
        management.updateMember(1,Map.of("addressLimit",0));
        coordinator.saveManagedAddress(first,Map.of("label","已有效地址允许改标签"));
        coordinator.saveManagedAddress(first,Map.of("enabled",false));
        coordinator.saveManagedAddress(second,Map.of("enabled",false));
        assertTrue(activeAddressIds(1).isEmpty());
        assertEquals("server.compute.v6.addressLimitReached",assertThrows(BusinessException.class,
                ()->coordinator.saveAddress(1,addressRequest())).getMessage());
        assertEquals("server.compute.v6.addressLimitReached",assertThrows(BusinessException.class,
                ()->coordinator.saveManagedAddress(first,Map.of("enabled",true))).getMessage());
        assertEquals("server.compute.v6.addressLimitReached",assertThrows(BusinessException.class,
                ()->coordinator.saveAddress(1,addressRequest("T"+"3".repeat(33)))).getMessage());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address",Integer.class));
        assertTrue(activeAddressIds(1).isEmpty());assertNoWithdrawal();
    }

    @Test void removedAddressReusesItsIdAndReleasesCapacityWithoutChangingWithdrawalHistory() {
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('addressLimit','1')");
        long first=number(coordinator.saveAddress(1,addressRequest()).get("id"));
        var withdrawal=coordinator.withdraw(1,withdrawalRequest("removed-address-history"));
        long order=number(withdrawal.get("id"));
        management.directApproveWithdrawal(order,"隔离用例确认完成",1);
        var originalOrder=data.one("SELECT * FROM compute_withdrawal WHERE id=?",order);
        var originalLedger=data.rows("SELECT * FROM compute_ledger ORDER BY id");
        business.deleteAddress(1,first);
        assertTrue(activeAddressIds(1).isEmpty());
        assertTrue(jdbc.queryForObject("SELECT removed FROM compute_address WHERE id=?",Boolean.class,first));
        assertEquals(first,number(coordinator.saveAddress(1,addressRequest()).get("id")));
        assertEquals(List.of(first),activeAddressIds(1));
        business.deleteAddress(1,first);
        long second=number(coordinator.saveAddress(1,addressRequest(OTHER)).get("id"));
        assertEquals(List.of(second),activeAddressIds(1));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address",Integer.class));
        assertEquals(2,((List<?>)management.addresses(Map.of("username","member1")).get("records")).size());
        assertEquals(originalOrder,data.one("SELECT * FROM compute_withdrawal WHERE id=?",order));
        assertEquals(originalLedger,data.rows("SELECT * FROM compute_ledger ORDER BY id"));
        assertUsdWallet("80","0");
    }

    @Test void sameMemberCanRebindDisabledAddressWithOriginalIdButMustHaveCapacity() {
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('addressLimit','1')");
        long first=number(coordinator.saveAddress(1,addressRequest()).get("id"));
        coordinator.saveManagedAddress(first,Map.of("enabled",false));
        assertEquals(first,number(coordinator.saveAddress(1,addressRequest()).get("id")));
        assertEquals(List.of(first),activeAddressIds(1));
        assertEquals(first,number(coordinator.saveAddress(1,addressRequest()).get("id")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address",Integer.class));
        coordinator.saveManagedAddress(first,Map.of("enabled",false));
        long second=number(coordinator.saveAddress(1,addressRequest(OTHER)).get("id"));
        assertEquals("server.compute.v6.addressLimitReached",assertThrows(BusinessException.class,
                ()->coordinator.saveAddress(1,addressRequest())).getMessage());
        assertEquals(List.of(second),activeAddressIds(1));
        assertFalse(jdbc.queryForObject("SELECT enabled FROM compute_address WHERE id=?",Boolean.class,first));
        assertNoWithdrawal();
    }

    @Test void disabledAddressMayBeBoundByAnotherMemberButActiveOwnerPreventsReactivation() {
        long first=number(coordinator.saveAddress(1,addressRequest()).get("id"));
        assertEquals("server.compute.v15.addressAlreadyBound",assertThrows(BusinessException.class,
                ()->coordinator.saveAddress(2,addressRequest())).getMessage());
        coordinator.saveManagedAddress(first,Map.of("enabled",false));
        long second=number(coordinator.saveAddress(2,addressRequest()).get("id"));
        assertTrue(activeAddressIds(1).isEmpty());assertEquals(List.of(second),activeAddressIds(2));
        assertEquals("server.compute.v15.addressAlreadyBound",assertThrows(BusinessException.class,
                ()->coordinator.saveAddress(1,addressRequest())).getMessage());
        assertEquals("server.compute.v15.addressAlreadyBound",assertThrows(BusinessException.class,
                ()->coordinator.saveManagedAddress(first,Map.of("enabled",true))).getMessage());
        assertFalse(jdbc.queryForObject("SELECT enabled FROM compute_address WHERE id=?",Boolean.class,first));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address",Integer.class));
        assertNoWithdrawal();
    }

    @Test void concurrentBindingsForSameMemberCannotBothTakeTheLastSlot() throws Exception {
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('addressLimit','1')");
        var ready=new CountDownLatch(2);var release=new CountDownLatch(1);
        when(client.request(eq("customer-addresses/query"),anyMap())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            ready.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return Map.of("registered",false);
        });
        var executor=Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures=new ArrayList<>();
            for(String address:List.of(ADDRESS,OTHER))futures.add(executor.submit(()->{
                try{return coordinator.saveAddress(1,addressRequest(address));}catch(BusinessException exception){return exception;}
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));release.countDown();
            List<Object> outcomes=new ArrayList<>();
            for(var future:futures)outcomes.add(future.get(10,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter(Map.class::isInstance).count());
            var failures=outcomes.stream().filter(BusinessException.class::isInstance).map(BusinessException.class::cast).toList();
            assertEquals(1,failures.size());assertEquals("server.compute.v6.addressLimitReached",failures.get(0).getMessage());
            assertEquals(1,activeAddressIds(1).size());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address",Integer.class));
            assertNoWithdrawal();
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void adminScopeIsRecheckedAfterRemoteBeforeFreeze() {
        var request=withdrawalRequest("admin-scope-race");request.remove("payPassword");request.put("memberId",1);request.put("remark","范围变化");
        when(client.request(anyString(),anyMap())).thenAnswer(call->{SecurityContextHolder.clearContext();return Map.of("registered",false);});
        assertThrows(BusinessException.class,()->coordinator.manualWithdrawal(request));
        assertNoWithdrawal();
    }

    @Test void coordinatorRejectsAnExistingTransactionWithoutExternalRequests() {
        var request=withdrawalRequest("withdraw-nested-tx");
        assertThrows(IllegalStateException.class,()->tx.execute(status->coordinator.withdraw(1,request)));
        verifyNoInteractions(client);assertNoWithdrawal();
    }
}
