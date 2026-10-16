package org.token.service.compute;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实生产资金事务的隔离回归：新逐笔充值与独立归集顺序都不能重复发钱。 */
@Tag("regression")
class ComputeMcagReceiptTest {
    ComputeMcagFixture fixture;
    ComputeMcagReceiptService service;
    ComputeMcagCollectionService collections;
    @BeforeEach void setup() throws Exception {
        fixture=new ComputeMcagFixture();fixture.setup();
        service=new ComputeMcagReceiptService(fixture.data,fixture.business,fixture.config,fixture.deposits,new ComputeMcagChannelService(fixture.data,fixture.config));
        collections=new ComputeMcagCollectionService(fixture.data,fixture.config,fixture.deposits);
    }
    Map<String,Object> order() {
        return fields("merchant_id","mch_token","environment","LIVE","order_id","deposit_new_1","receipt_id","receipt_1","transfer_event_id","transfer_1",
                "customer_id","token_1_compute","asset_id","USDT_TRON","network_id","TRON","address_id","addr_1","address","T1"+"a".repeat(32),
                "purpose","CUSTOMER_DEPOSIT","accounting_model","SINGLE_RECEIPT","status","CONFIRMED","resource_version",1,"amount","12.12345678",
                "tx_hash","hash1","transfer_locator","log:1","sender_address","sender","chain_status","CONFIRMED","current_confirmations",25,"required_confirmations",20,
                "finality_satisfied",true,"risk_status","CLEAR","credit_decision","CREDITED","decimals",8);
    }
    private String apply(Map<String,Object> order){return fixture.tx.execute(status->service.apply(order,null));}
    private long count(String table){return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private BigDecimal balance(){return fixture.jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='COMPUTE' AND currency='USDT'",BigDecimal.class);}
    @Test void customerIdTextCannotInventAnUnregisteredMemberIdentity() {
        assertNull(fixture.deposits.customerIdentity("token_2_compute"));
        var forged=order();forged.putAll(fields("customer_id","token_2_compute","address_id","unregistered-address"));
        assertEquals("REVIEW",apply(forged));
        assertEquals("UNBOUND_ADDRESS",fixture.data.one("SELECT reason FROM compute_mcag_deposit_order").get("reason"));
        assertEquals(0,count("compute_deposit"));assertEquals(0,count("compute_ledger"));
    }
    @Test void disabledAddressRetainsDepositWithoutAnyWalletCreditOrNotification() {
        fixture.jdbc.update("UPDATE compute_mcag_address SET disabled_at=CURRENT_TIMESTAMP");
        assertEquals("REVIEW",apply(order()));assertEquals("REVIEW",apply(order()));
        assertEquals(1,count("compute_deposit"));assertEquals(0,count("compute_ledger"));assertEquals(0,count("compute_finance_snapshot"));
        assertEquals("MCAG_DISABLED_ADDRESS",fixture.data.one("SELECT * FROM compute_deposit").get("paymentStatus"));
        assertEquals("DISABLED_ADDRESS",fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("reason"));
        var later=order();later.put("resource_version",2);assertEquals("REVIEW",apply(later));assertEquals(0,count("compute_ledger"));
        verifyNoInteractions(fixture.messages);
    }
    @Test void disablingAfterCreditDoesNotRevokeOrRepeatTheHistoricalCredit() {
        assertEquals("CREDITED",apply(order()));var original=balance();
        fixture.jdbc.update("UPDATE compute_mcag_address SET disabled_at=CURRENT_TIMESTAMP");
        assertEquals("CREDITED",apply(order()));assertEquals(original,balance());assertEquals(1,count("compute_ledger"));
    }
    @Test void creditedOrderWithoutCollectionStillHasReadablePaymentDetails() {
        assertEquals("CREDITED",apply(order()));
        var rows=fixture.data.rows("SELECT * FROM compute_deposit");
        ComputePaymentRecords.enrich(fixture.data,"deposits",rows,true);
        assertEquals(1,rows.size());
        var row=rows.get(0);
        assertEquals("deposit_new_1",row.get("gatewayReference"));
        assertEquals("SINGLE_RECEIPT",row.get("accountingModel"));
        assertEquals(Map.of(),row.get("collectionDetails"));
        assertNull(row.get("collectionStatus"));
        assertEquals(0,new BigDecimal("12.12345678").compareTo(decimal(row.get("amount"))));
        assertEquals(1,count("compute_ledger"));
    }
    @Test void repeatedOrderReviewAndReconfirmationKeepOneWalletCreditAndOneNotification() {
        assertEquals("CREDITED",apply(order()));assertEquals("CREDITED",apply(order()));
        BigDecimal original=balance();
        var review=order();review.putAll(fields("status","REVIEW","resource_version",2,"chain_status","ORPHANED","finality_satisfied",false));
        assertEquals("REVIEW",apply(review));assertEquals(original,balance());
        assertEquals("MCAG_REVERSED",fixture.data.one("SELECT * FROM compute_deposit").get("paymentStatus"));
        var reconfirmed=order();reconfirmed.put("resource_version",3);assertEquals("CREDITED",apply(reconfirmed));assertEquals("STALE",apply(review));
        assertEquals(original,balance());assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));assertEquals(1,count("compute_finance_snapshot"));
        verify(fixture.messages,times(1)).depositApproved(anyLong(),eq(1L),any(),eq("USDT"));
    }
    @Test void concurrentReplaysAndDifferentTransferInSameTransactionAreIndependent() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            var calls=new ArrayList<Future<String>>();for(int i=0;i<8;i++)calls.add(pool.submit(()->apply(order())));
            for(var call:calls)assertEquals("CREDITED",call.get(20,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        var second=order();second.putAll(fields("order_id","deposit_new_2","receipt_id","receipt_2","transfer_event_id","transfer_2","transfer_locator","log:2"));
        assertEquals("CREDITED",apply(second));assertEquals(2,count("compute_ledger"));
        assertEquals(0,new BigDecimal("24.24691356").compareTo(balance()));
        var forged=order();forged.putAll(fields("order_id","forged","receipt_id","receipt_3","transfer_event_id","transfer_3"));
        assertThrows(BusinessException.class,()->apply(forged));assertEquals(2,count("compute_ledger"));
    }
    @Test void trustedCustomerAndMappingAddHistoricalBindingWithoutChangingCurrentAddress() {
        var next=order();next.putAll(fields("address_id","new_addr","address","T2"+"b".repeat(32)));
        assertEquals("CREDITED",apply(next));assertEquals(2,count("compute_mcag_address"));
        assertEquals("addr_1",fixture.data.one("SELECT a.address_id FROM compute_mcag_address_current c JOIN compute_mcag_address a ON a.id=c.binding_id").get("addressId"));
        var other=order();other.putAll(fields("order_id","unknown","receipt_id","unknown_r","transfer_event_id","unknown_t","customer_id","forged_customer","address_id","unknown_a","transfer_locator","log:3"));
        assertEquals("REVIEW",apply(other));assertEquals(1,count("compute_ledger"));
    }
    @Test void tenantTestMemberAndUnrepresentableAmountNeverCredit() {
        var bad=order();bad.put("merchant_id","another");assertThrows(BusinessException.class,()->apply(bad));
        var test=order();test.putAll(fields("customer_id","token_2_compute","address_id","addr_2","address","T2"+"a".repeat(32)));
        assertEquals("REVIEW",apply(test));assertEquals(0,count("compute_ledger"));
        fixture.jdbc.update("DELETE FROM compute_mcag_deposit_order");
        var precise=order();precise.putAll(fields("decimals",30,"amount","1.0000000000000000000000000000001"));assertEquals("REVIEW",apply(precise));assertEquals(0,count("compute_ledger"));
        assertEquals("AMOUNT_PRECISION",fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("reason"));
        assertEquals(0,count("compute_deposit"));
    }

    private Map<String,Object> highPrecisionOrder() {
        ComputeMcagTestSchema.seedMapping(fixture.jdbc,"USDT","BEP20","USDT_BSC","BSC_MAINNET",18);
        var precise=order();
        precise.putAll(fields("asset_id","USDT_BSC","network_id","BSC_MAINNET","address_id","addr_bsc",
                "address","0x"+"a".repeat(40),"decimals",18,"amount","11.696130139510566704"));
        return precise;
    }

    @Test void chainNativePrecisionIsPreservedWhileOnlyUsdCreditRoundsDown() {
        var precise=highPrecisionOrder();
        assertEquals("CREDITED",apply(precise));
        assertEquals("CREDITED",apply(precise));
        BigDecimal raw=new BigDecimal("11.696130139510566704"),usd=new BigDecimal("11.69613013");
        var local=fixture.data.one("SELECT * FROM compute_deposit");
        assertEquals(0,raw.compareTo(decimal(local.get("amount"))));
        assertEquals(0,usd.compareTo(decimal(local.get("accountingAmount"))));
        Object snapshotNative=fixture.data.jsonMap(local.get("accountingQuoteJson")).get("nativeAmount");
        assertInstanceOf(String.class,snapshotNative);assertEquals(0,raw.compareTo(decimal(snapshotNative)));
        assertEquals(0,raw.compareTo(fixture.jdbc.queryForObject("SELECT native_amount FROM compute_finance_snapshot",BigDecimal.class)));
        assertEquals(0,raw.compareTo(fixture.jdbc.queryForObject("SELECT native_net_amount FROM compute_finance_snapshot",BigDecimal.class)));
        assertEquals(0,usd.compareTo(balance()));
        assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));assertEquals(1,count("compute_finance_snapshot"));
        verify(fixture.messages,times(1)).depositApproved(anyLong(),eq(1L),argThat(value->raw.compareTo(value)==0),eq("USDT"));
    }

    @Test void previouslyRecordedPrecisionReviewRecoversThroughAuthoritativeReplayOnce() {
        var precise=highPrecisionOrder();
        fixture.data.insert("compute_mcag_deposit_order",fields("merchant_id","mch_token","environment","LIVE",
                "external_order_id",precise.get("order_id"),"receipt_id",precise.get("receipt_id"),"transfer_event_id",precise.get("transfer_event_id"),
                "customer_id",precise.get("customer_id"),"address_id",precise.get("address_id"),"asset_id",precise.get("asset_id"),"network_id",precise.get("network_id"),
                "address",precise.get("address"),"amount_text",precise.get("amount"),"tx_hash",precise.get("tx_hash"),"transfer_locator",precise.get("transfer_locator"),
                "resource_version",1,"member_id",1,"state","REVIEW","reason","AMOUNT_PRECISION","remote_status","CONFIRMED",
                "snapshot_json","{}","created_at",now(),"updated_at",now()));
        long auditId=number(fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("id"));
        assertEquals("CREDITED",apply(precise));assertEquals("CREDITED",apply(precise));
        var repaired=fixture.data.one("SELECT * FROM compute_mcag_deposit_order");
        assertEquals(auditId,number(repaired.get("id")));assertEquals("",repaired.get("reason"));
        assertNotNull(repaired.get("recordId"));assertEquals(repaired.get("recordId"),repaired.get("localDepositId"));
        assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));
    }

    @Test void highPrecisionPendingAndDisabledAddressesStayVisibleWithoutCredit() {
        var precise=highPrecisionOrder();precise.put("finality_satisfied",false);
        assertEquals("REVIEW",apply(precise));
        assertEquals(1,count("compute_deposit"));assertEquals(0,count("compute_ledger"));
        assertEquals("CHAIN_PENDING",fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("reason"));
        fixture.jdbc.update("UPDATE compute_mcag_address SET disabled_at=CURRENT_TIMESTAMP WHERE address_id='addr_bsc'");
        precise.put("finality_satisfied",true);
        assertEquals("REVIEW",apply(precise));
        assertEquals("DISABLED_ADDRESS",fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("reason"));
        assertEquals(1,count("compute_deposit"));assertEquals(0,count("compute_ledger"));verifyNoInteractions(fixture.messages);
    }

    @Test void amountBelowOneUsdUnitRemainsVisibleWithoutCreditOrConsumedIdempotency() {
        var precise=highPrecisionOrder();precise.put("amount","0.000000001");
        assertEquals("REVIEW",apply(precise));assertEquals("REVIEW",apply(precise));
        var local=fixture.data.one("SELECT * FROM compute_deposit");
        assertEquals("PENDING",local.get("status"));assertEquals("AMOUNT_PRECISION",local.get("accountingStatus"));
        assertEquals(0,new BigDecimal("0.000000001").compareTo(decimal(local.get("amount"))));
        assertEquals("ACCOUNTING_PRECISION",fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("reason"));
        assertNull(fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("localDepositId"));
        assertEquals(1,count("compute_deposit"));assertEquals(0,count("compute_ledger"));assertEquals(0,count("compute_finance_snapshot"));
        verifyNoInteractions(fixture.messages);
    }

    @Test void highPrecisionReorgAndReconfirmationNeverRepeatCredit() {
        var precise=highPrecisionOrder();assertEquals("CREDITED",apply(precise));
        precise.putAll(fields("status","REVIEW","resource_version",2,"chain_status","ORPHANED","finality_satisfied",false));
        assertEquals("REVIEW",apply(precise));
        precise.putAll(fields("status","CONFIRMED","resource_version",3,"chain_status","CONFIRMED","finality_satisfied",true));
        assertEquals("CREDITED",apply(precise));
        assertEquals(0,new BigDecimal("11.69613013").compareTo(balance()));assertEquals(1,count("compute_ledger"));
    }

    @Test void nativePrecisionHonorsAssetDecimalsAndIgnoresOnlyZeroPadding() {
        var precise=highPrecisionOrder();precise.put("decimals",6);
        assertEquals("REVIEW",apply(precise));assertEquals(0,count("compute_deposit"));assertEquals(0,count("compute_ledger"));
        assertEquals("AMOUNT_PRECISION",fixture.data.one("SELECT * FROM compute_mcag_deposit_order").get("reason"));
        precise.put("decimals",18);precise.put("amount","11.696130139510566704000000000000");
        assertEquals("CREDITED",apply(precise));assertEquals(1,count("compute_ledger"));
        precise.put("decimals","18.5");assertThrows(BusinessException.class,()->apply(precise));
        assertEquals(1,count("compute_ledger"));
    }

    @Test void thirtyDecimalNativeAmountCanReplayConcurrentlyWithoutDuplicateCredit() throws Exception {
        var precise=highPrecisionOrder();precise.putAll(fields("decimals",30,"amount","11.696130139510566704123456789123"));
        var pool=Executors.newFixedThreadPool(4);
        try {
            var calls=new ArrayList<Future<String>>();
            for(int i=0;i<8;i++)calls.add(pool.submit(()->apply(precise)));
            for(var call:calls)assertEquals("CREDITED",call.get(20,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertEquals(0,decimal(precise.get("amount")).compareTo(fixture.jdbc.queryForObject("SELECT amount FROM compute_deposit",BigDecimal.class)));
        assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));assertEquals(1,count("compute_finance_snapshot"));
        var changed=new LinkedHashMap<>(precise);changed.put("amount","11.696130139510566704123456789124");
        assertThrows(BusinessException.class,()->apply(changed));assertEquals(1,count("compute_ledger"));
    }

    @Test void unmigratedOrderColumnCannotSilentlyRoundAndCreditNativeAmount() {
        fixture.jdbc.execute("ALTER TABLE compute_deposit ALTER COLUMN amount DECIMAL(28,8)");
        assertThrows(BusinessException.class,()->apply(highPrecisionOrder()));
        assertEquals(0,count("compute_deposit"));assertEquals(0,count("compute_mcag_deposit_order"));assertEquals(0,count("compute_ledger"));
        verifyNoInteractions(fixture.messages);
    }
    @Test void modernSweepBeforeAndAfterOrderNeverCreatesSecondDepositIncludingUncreditedDust() {
        var batch=batch("RECEIPT_SWEEP");
        assertEquals("TRACKED",fixture.tx.execute(s->collections.apply(batch,null)));assertEquals(0,count("compute_deposit"));
        assertEquals("CREDITED",apply(order()));assertEquals("TRACKED",fixture.tx.execute(s->collections.apply(batch,null)));
        assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));
        assertNull(fixture.data.one("SELECT * FROM compute_mcag_collection_batch").get("localDepositId"));
    }
    @Test void failedSweepAfterCreditedOrderNeverChangesWalletCredit() {
        assertEquals("CREDITED",apply(order()));var original=balance();
        var batch=batch("RECEIPT_SWEEP");batch.put("sweep_status","FAILED");
        assertEquals("REVIEW",fixture.tx.execute(s->collections.apply(batch,null)));
        assertEquals(original,balance());assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));
        assertNull(fixture.data.one("SELECT * FROM compute_mcag_collection_batch").get("localDepositId"));
    }

    @Test void collectionReviewDoesNotSubstituteForDepositOrderReview() {
        assertEquals("CREDITED",apply(order()));var original=balance();
        var batch=batch("RECEIPT_SWEEP");batch.put("status","REVIEW");
        assertEquals("REVIEW",fixture.tx.execute(s->collections.apply(batch,null)));
        assertEquals("MCAG_VERIFIED",fixture.data.one("SELECT * FROM compute_deposit").get("paymentStatus"));
        var review=order();review.putAll(fields("status","REVIEW","resource_version",2,"chain_status","ORPHANED","finality_satisfied",false));
        assertEquals("REVIEW",apply(review));assertEquals("MCAG_REVERSED",fixture.data.one("SELECT * FROM compute_deposit").get("paymentStatus"));
        assertEquals(original,balance());assertEquals(1,count("compute_ledger"));
    }

    @Test void lateCollectionReconfirmationAfterNewOrderOnlyRecordsStateAndEvent() {
        assertEquals("CREDITED",apply(order()));var original=balance();
        var batch=batch("RECEIPT_SWEEP");
        var event=fields("merchant_id","mch_token","environment","LIVE","event_id","late_batch","event_sequence",1,
                "resource_type","COLLECTION_BATCH","resource_id","batch1","resource_version",1,"event_type","collection.reconfirmed");
        for(int i=0;i<2;i++)assertEquals("TRACKED",fixture.tx.execute(s->collections.apply(batch,event)));
        assertEquals(1,count("compute_mcag_event"));
        assertEquals(original,balance());assertEquals(1,count("compute_ledger"));assertEquals(1,count("compute_finance_snapshot"));
        verify(fixture.messages,times(1)).depositApproved(anyLong(),eq(1L),any(),eq("USDT"));
    }

    @Test void reviewBeforeFirstCreditIsVisibleAndLaterConfirmationPostsOnce() {
        var review=order();review.putAll(fields("status","REVIEW","chain_status","ORPHANED","finality_satisfied",false));
        assertEquals("REVIEW",apply(review));assertEquals(1,count("compute_deposit"));assertEquals(0,count("compute_ledger"));
        var confirmed=order();confirmed.put("resource_version",2);assertEquals("CREDITED",apply(confirmed));assertEquals("CREDITED",apply(confirmed));
        assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));assertEquals("APPROVED",fixture.data.one("SELECT * FROM compute_deposit").get("status"));
    }
    @Test void failureInExistingNotificationRollsBackOrderAndAllFunds() {
        doThrow(new IllegalStateException("模拟通知失败")).when(fixture.messages).depositApproved(anyLong(),anyLong(),any(),anyString());
        assertThrows(IllegalStateException.class,()->apply(order()));assertEquals(0,count("compute_mcag_deposit_order"));assertEquals(0,count("compute_deposit"));assertEquals(0,count("compute_ledger"));
    }
    Map<String,Object> batch(String model) {
        var value=ComputeMcagBatchReaderTest.summary(2);
        value.putAll(fields("accounting_model",model,"resource_version",1,"amount","12.17345678","credited_amount","12.12345678","uncredited_amount","0.05"));
        return value;
    }
}
