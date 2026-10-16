package org.token.service.compute;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** USD 充值入账的隔离资金边界；行情、链上订单及数据库全部来自本测试夹具。 */
@Tag("regression")
class ComputeUsdDepositTest {
    private ComputeMcagFixture fixture;
    private ComputeMcagReceiptService receipts;

    @BeforeEach void setup() throws Exception {
        fixture=new ComputeMcagFixture();fixture.setup();
        fixture.jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('TRX','TRC20',1)");
        TokenTestSchema.seedCurrencies(fixture.jdbc);
        ComputeMcagTestSchema.seedMapping(fixture.jdbc,"TRX","TRC20","TRX_TRON","TRON",8);
        var channel=new ComputeMcagChannelService(fixture.data,fixture.config).requireChannel("TRX","TRC20");
        fixture.tx.executeWithoutResult(status->fixture.deposits.bind(1,"COMPUTE",channel,fields(
                "merchant_id","mch_token","environment","LIVE","customer_id","token_1_compute",
                "network_id","TRON","address_id","addr_trx","address","T1"+"a".repeat(32),
                "purpose","CUSTOMER_DEPOSIT","status","ACTIVE","monitoring_ready",true)));
        receipts=new ComputeMcagReceiptService(fixture.data,fixture.business,fixture.config,fixture.deposits,
                new ComputeMcagChannelService(fixture.data,fixture.config));
    }

    private Map<String,Object> order() {
        var result=new ComputeMcagReceiptTest().order();
        result.putAll(fields("asset_id","TRX_TRON","address_id","addr_trx","amount","100"));
        return result;
    }
    private void rate(String value) {
        fixture.jdbc.update("INSERT INTO compute_exchange_rate(rate_type,currency,rate,provider,source_id,source_at,refreshed_at) VALUES('ASSET','TRX',?,'MCAG','TRX',TIMESTAMP '2026-09-22 01:00:00',TIMESTAMP '2026-09-22 01:00:01') ON DUPLICATE KEY UPDATE rate=VALUES(rate)",new BigDecimal(value));
    }
    private String apply(Map<String,Object> order) { return fixture.tx.execute(status->receipts.apply(order,null)); }
    private Map<String,Object> deposit() { return fixture.data.one("SELECT * FROM compute_deposit"); }
    private long count(String table) { return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private void assertAmount(String expected,Object actual) { assertEquals(0,new BigDecimal(expected).compareTo(decimal(actual))); }
    private void assertUsdPosted(String expected) {
        var recorded=deposit();
        assertEquals("APPROVED",recorded.get("status"));assertEquals("POSTED",recorded.get("accountingStatus"));
        assertEquals("TRX",recorded.get("currency"));assertEquals("USD",recorded.get("accountingCurrency"));
        assertAmount(expected,recorded.get("accountingAmount"));
        var ledger=fixture.data.one("SELECT * FROM compute_ledger WHERE kind='DEPOSIT'");
        assertEquals("USDT",ledger.get("currency"));assertAmount(expected,ledger.get("amount"));
        var wallet=fixture.data.one("SELECT * FROM compute_wallet WHERE member_id=1 AND wallet_type='COMPUTE' AND currency='USDT'");
        assertAmount(expected,wallet.get("available"));
        assertAmount(expected,fixture.data.one("SELECT * FROM compute_finance_snapshot").get("usdAmount"));
        assertEquals(0L,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet WHERE currency='TRX'",Long.class));
    }

    @Test void nativeDepositPostsFixedUsdAndReplayDoesNotRevalue() {
        rate("0.123456789");
        assertEquals("CREDITED",apply(order()));assertUsdPosted("12.34567890");
        assertAmount("100",deposit().get("amount"));assertAmount("0.123456789",deposit().get("accountingRate"));
        var snapshot=fixture.data.jsonMap(deposit().get("accountingQuoteJson"));
        assertEquals("MCAG",snapshot.get("provider"));assertEquals("TRX",snapshot.get("nativeCurrency"));
        rate("2");assertEquals("CREDITED",apply(order()));assertUsdPosted("12.34567890");
        assertEquals(1,count("compute_ledger"));assertEquals(1,count("compute_finance_snapshot"));
        verify(fixture.messages,times(1)).depositApproved(anyLong(),eq(1L),any(),anyString());
    }

    @Test void authoritativeNativeDepositUpgradesAtExactUsdThresholdOnlyAfterPosting() {
        fixture.jdbc.update("INSERT INTO compute_level(id,name,threshold,enabled,vip_validity_days,vip_cycle_policy,auto_provider_ids) VALUES(1,'基础等级',0,1,0,'KEEP','[]'),(2,'十元等级',10,1,180,'RESET','[]')");
        assertEquals("REVIEW",apply(order()));
        assertEquals(1L,fixture.jdbc.queryForObject("SELECT level FROM compute_member WHERE id=1",Long.class),"缺报价没有真实入账，不能升级");
        rate("0.1");
        assertEquals("CREDITED",apply(order()));assertUsdPosted("10");
        assertEquals(2L,fixture.jdbc.queryForObject("SELECT level FROM compute_member WHERE id=1",Long.class),"按固定USD入账余额恰好达到门槛即可升级");
        var period=fixture.data.one("SELECT vip_started_at,vip_expires_at FROM compute_member WHERE id=1");
        assertNotNull(period.get("vipStartedAt"));assertNotNull(period.get("vipExpiresAt"));
        rate("9");
        assertEquals("CREDITED",apply(order()));assertUsdPosted("10");
        assertEquals(period,fixture.data.one("SELECT vip_started_at,vip_expires_at FROM compute_member WHERE id=1"));
        assertEquals(1,count("compute_ledger"));
    }

    @Test void missingPricePersistsWithoutClaimingCreditThenSameOrderCanResume() {
        assertEquals("REVIEW",apply(order()));assertEquals("REVIEW",apply(order()));
        assertEquals("PENDING",deposit().get("status"));assertEquals("RATE_PENDING",deposit().get("accountingStatus"));
        assertEquals("MCAG_RATE_PENDING",deposit().get("paymentStatus"));
        var tracked=fixture.data.one("SELECT * FROM compute_mcag_deposit_order");
        assertNotNull(tracked.get("recordId"));assertNull(tracked.get("localDepositId"));
        assertEquals("RATE_UNAVAILABLE",tracked.get("reason"));
        assertEquals(0,count("compute_ledger"));assertEquals(0,count("compute_finance_snapshot"));
        verifyNoInteractions(fixture.messages);
        rate("0.25");assertEquals("CREDITED",apply(order()));assertUsdPosted("25");
        assertEquals("CREDITED",apply(order()));assertEquals(1,count("compute_ledger"));
        assertNotNull(fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("localDepositId"));
    }

    @Test void polygonUsdt0PostsOneToOneWithoutMarketQuoteAndKeepsNativeIdentity() {
        fixture.jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT0','POL',1)");
        TokenTestSchema.seedCurrencies(fixture.jdbc);
        ComputeMcagTestSchema.seedMapping(fixture.jdbc,"USDT0","POL","USDT0_POL","POL",8);
        var channel=new ComputeMcagChannelService(fixture.data,fixture.config).requireChannel("USDT0","POL");
        String address="0x"+"1".repeat(40);
        fixture.tx.executeWithoutResult(status->fixture.deposits.bind(1,"COMPUTE",channel,fields(
                "merchant_id","mch_token","environment","LIVE","customer_id","token_1_compute",
                "network_id","POL","address_id","addr_pol","address",address,
                "purpose","CUSTOMER_DEPOSIT","status","ACTIVE","monitoring_ready",true)));
        var stable=order();stable.putAll(fields("asset_id","USDT0_POL","network_id","POL","address_id","addr_pol","address",address,"amount","7.25"));
        assertEquals("CREDITED",apply(stable));
        assertEquals("USDT0",deposit().get("currency"));assertEquals("POL",deposit().get("network"));
        assertAmount("1",deposit().get("accountingRate"));assertAmount("7.25",deposit().get("accountingAmount"));
        var ledger=fixture.data.one("SELECT * FROM compute_ledger WHERE kind='DEPOSIT'");
        assertEquals("USDT",ledger.get("currency"));assertAmount("7.25",ledger.get("amount"));
        assertEquals(0,count("compute_exchange_rate"));assertEquals(1,count("compute_ledger"));
    }

    @Test void restoredPriceUsesAuthoritativeQueueRecheckWithoutNewCallback() {
        assertEquals("REVIEW",apply(order()));rate("0.2");
        var client=mock(ComputeMcagClient.class);
        var transactional=mock(ComputeMcagReceiptService.class);
        when(transactional.apply(anyMap(),isNull())).thenAnswer(call->apply(call.getArgument(0)));
        when(client.request("deposit-orders/query",Map.of("order_id","deposit_new_1","limit",1))).thenReturn(Map.of("items",List.of(order())));
        when(client.request("deposit-orders/query",Map.of("purpose","CUSTOMER_DEPOSIT","limit",20))).thenReturn(Map.of("items",List.of(),"has_more",false));
        fixture.config.setPollEnabled(true);
        var gateway=new ComputeMcagPaymentGateway(fixture.config,client,fixture.deposits,new ComputeCurrencyService(fixture.data),fixture.data,
                mock(ComputeMcagPayoutService.class),mock(ComputeMcagCollectionService.class),transactional,
                new ComputeMcagChannelService(fixture.data,fixture.config));
        gateway.watchOrders();assertUsdPosted("20");gateway.watchOrders();
        verify(client,times(1)).request("deposit-orders/query",Map.of("order_id","deposit_new_1","limit",1));
        assertEquals(1,count("compute_ledger"));
    }

    @Test void addressDisabledWhileAwaitingPriceNeverTokenAfterPriceRecovery() {
        assertEquals("REVIEW",apply(order()));
        fixture.jdbc.update("UPDATE compute_mcag_address SET disabled_at=CURRENT_TIMESTAMP WHERE asset_id='TRX_TRON'");
        rate("0.2");assertEquals("REVIEW",apply(order()));
        assertEquals("DISABLED_ADDRESS",fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("reason"));
        assertEquals("MCAG_DISABLED_ADDRESS",deposit().get("paymentStatus"));assertEquals(0,count("compute_ledger"));
        assertNull(fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("localDepositId"));
    }

    @Test void precisionUnderflowPersistsReviewInsteadOfPostingZeroOrLosingNativeReceipt() {
        rate("0.00000001");var tiny=order();tiny.put("amount","0.00000001");
        assertEquals("REVIEW",apply(tiny));assertAmount("0.00000001",deposit().get("amount"));
        assertEquals("AMOUNT_PRECISION",deposit().get("accountingStatus"));
        assertEquals("ACCOUNTING_PRECISION",fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("reason"));
        assertEquals(0,count("compute_ledger"));assertNull(deposit().get("accountingAmount"));
        rate("10");assertEquals("REVIEW",apply(tiny));assertEquals(0,count("compute_ledger"));
    }

    @Test void precisionOverflowIsTrackedWithoutAnyWalletMutation() {
        rate("1000");var large=order();large.put("amount","999999999999999999");
        assertEquals("REVIEW",apply(large));assertEquals("AMOUNT_PRECISION",deposit().get("accountingStatus"));
        assertEquals(1,count("compute_deposit"));assertEquals(0,count("compute_ledger"));assertEquals(0,count("compute_wallet"));
    }

    @Test void manualApprovalUsesSameDeferredPriceAndPreparedSnapshot() {
        long id=fixture.data.insert("compute_deposit",fields("member_id",1,"wallet_type","COMPUTE","currency","TRX","network","TRC20",
                "amount",100,"status","PENDING","payment_status","MANUAL_REVIEW","record_source","ADMIN_MANUAL","created_at",now()));
        fixture.tx.executeWithoutResult(status->fixture.business.reviewDeposit(id,true,"审核",1));
        assertEquals("RATE_PENDING",deposit().get("accountingStatus"));assertEquals(0,count("compute_ledger"));
        rate("0.123456789");
        fixture.tx.executeWithoutResult(status->{
            var locked=fixture.data.one("SELECT * FROM compute_deposit WHERE id=? FOR UPDATE",id);
            assertTrue(new ComputeDepositAccounting(fixture.data).prepare(locked).isPresent());
        });
        rate("9");fixture.tx.executeWithoutResult(status->fixture.business.reviewDeposit(id,true,"审核",1));
        assertUsdPosted("12.34567890");
    }

    @Test void alreadyApprovedNativeHistoryCannotBeConvertedByPreparation() {
        long id=fixture.data.insert("compute_deposit",fields("member_id",1,"wallet_type","COMPUTE","currency","TRX","network","TRC20",
                "amount",100,"status","APPROVED","payment_status","MANUAL_REVIEW","record_source","ADMIN_MANUAL","created_at",now()));
        rate("2");assertThrows(BusinessException.class,()->fixture.tx.executeWithoutResult(status->
                new ComputeDepositAccounting(fixture.data).prepare(fixture.data.one("SELECT * FROM compute_deposit WHERE id=? FOR UPDATE",id))));
        assertEquals(1,number(deposit().get("accountingVersion")));assertNull(deposit().get("accountingAmount"));assertEquals(0,count("compute_ledger"));
    }
}
