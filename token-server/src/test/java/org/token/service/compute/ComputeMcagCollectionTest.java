package org.token.service.compute;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;

/** 新模式归集只记状态；使用隔离 H2 验证原资金账本不变。 */
class ComputeMcagCollectionTest {
    private ComputeMcagFixture fixture;
    private ComputeMcagCollectionService collections;
    @BeforeEach void setup() throws Exception {
        fixture=new ComputeMcagFixture();fixture.setup();
        collections=new ComputeMcagCollectionService(fixture.data,fixture.config,fixture.deposits);
    }
    private Map<String,Object> batch(long version,String status,String sweep) {
        var value=ComputeMcagBatchReaderTest.summary(6000);
        value.putAll(fields("resource_version",version,"status",status,"sweep_status",sweep));return value;
    }
    private Map<String,Object> event(long sequence,long version) {
        return fields("merchant_id","mch_token","environment","LIVE","event_id","evt_"+sequence,"event_sequence",sequence,
                "resource_id","batch1","resource_type","COLLECTION_BATCH","resource_version",version,"event_type","collection.created");
    }
    private String apply(Map<String,Object> batch) {return fixture.tx.execute(tx->collections.apply(batch,null));}
    private int count(String table) {return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private void noUserAccounting() {
        assertEquals(0,count("compute_deposit"));assertEquals(0,count("compute_ledger"));
        // 夹具只绑定充值地址，尚无钱包。归集不能创建钱包或生成任何资金快照。
        assertEquals(0,count("compute_wallet"));assertEquals(0,count("compute_finance_snapshot"));
        assertEquals(0,BigDecimal.ZERO.compareTo(fixture.jdbc.queryForObject("SELECT COALESCE(SUM(available),0) FROM compute_wallet",BigDecimal.class)));
    }
    @Test void largeBatchAndRepeatedSummaryTrackWithoutReceiptRowsOrWalletCredit() {
        assertEquals("TRACKED",apply(batch(1,"READY","WAITING")));
        assertEquals("TRACKED",apply(batch(1,"READY","WAITING")));
        assertEquals(1,count("compute_mcag_collection_batch"));noUserAccounting();
        var stored=fixture.data.one("SELECT * FROM compute_mcag_collection_batch");
        assertNull(stored.get("recordId"));assertNull(stored.get("localDepositId"));
        assertEquals(6000,fixture.data.jsonMap(stored.get("collectionJson")).get("receipt_count"));
    }
    @Test void failureReorgReconfirmationAndStaleReplayOnlyChangeTrackingState() {
        assertEquals("TRACKED",apply(batch(1,"READY","WAITING")));
        assertEquals("REVIEW",apply(batch(2,"READY","FAILED")));
        assertEquals("TRACKED",apply(batch(3,"READY","SUCCEEDED")));
        assertEquals("REVIEW",apply(batch(4,"REVIEW","REVIEW")));
        assertEquals("STALE",apply(batch(3,"READY","SUCCEEDED")));
        assertEquals("TRACKED",apply(batch(5,"READY","SUCCEEDED")));noUserAccounting();
        assertEquals(5L,number(fixture.data.one("SELECT * FROM compute_mcag_collection_batch").get("resourceVersion")));
    }
    @Test void changedIdentityAmountOrReceiptCountFailsEvenForStaleSnapshot() {
        apply(batch(3,"READY","WAITING"));
        for(String key:List.of("customer_id","address_id","asset_id","network_id","address","purpose","amount","receipt_count")) {
            var value=batch(2,"READY","WAITING");
            value.put(key,key.equals("receipt_count")?6001:key.equals("amount")?"6001":"changed");
            if(key.equals("amount"))value.put("uncredited_amount","2");
            assertThrows(BusinessException.class,()->apply(value),key);
        }
        assertEquals(1,count("compute_mcag_collection_batch"));noUserAccounting();
    }
    @Test void sameVersionCannotRegressStatusAndEventCannotClaimNewerVersion() {
        apply(batch(3,"READY","SUCCEEDED"));
        assertThrows(BusinessException.class,()->apply(batch(3,"READY","WAITING")));
        assertThrows(BusinessException.class,()->fixture.tx.execute(tx->collections.apply(batch(3,"READY","SUCCEEDED"),event(1,4))));
        assertEquals(0,count("compute_mcag_event"));noUserAccounting();
    }
    @Test void platformCollectionWithEmptyCustomerIsPersistedWithoutMemberAccounting() {
        var platform=ComputeMcagBatchReaderTest.serviceFeeSummary();
        assertEquals("TRACKED",fixture.tx.execute(tx->collections.apply(platform,event(1,3))));
        var row=fixture.data.one("SELECT * FROM compute_mcag_collection_batch");
        assertNull(row.get("memberId"));assertEquals("",row.get("customerId"));assertEquals(1,count("compute_mcag_event"));noUserAccounting();
    }
    @Test void duplicateEventIsIdempotentAndConflictingSequenceRollsBackUpdatedBatch() {
        var value=batch(1,"READY","WAITING");var first=event(1,1);
        for(int i=0;i<2;i++)assertEquals("TRACKED",fixture.tx.execute(tx->collections.apply(value,first)));
        var conflict=event(1,2);conflict.put("event_id","other_event");
        assertThrows(BusinessException.class,()->fixture.tx.execute(tx->collections.apply(batch(2,"READY","SUCCEEDED"),conflict)));
        assertEquals(1,count("compute_mcag_event"));assertEquals(1L,number(fixture.data.one("SELECT * FROM compute_mcag_collection_batch").get("resourceVersion")));noUserAccounting();
    }
    @Test void unboundAndTestCustomersRemainReviewableWithoutCrediting() {
        var unknown=batch(1,"READY","WAITING");unknown.put("customer_id","unknown");
        assertEquals("REVIEW",apply(unknown));
        assertEquals("UNBOUND_ADDRESS",fixture.data.one("SELECT * FROM compute_mcag_collection_batch WHERE batch_id='batch1'").get("reason"));
        fixture.jdbc.update("UPDATE compute_member SET is_test=0 WHERE id=2");
        fixture.tx.executeWithoutResult(tx->fixture.deposits.bind(2,"COMPUTE",fixture.config.getChannels().get("usdt-tron"),fields(
                "merchant_id","mch_token","environment","LIVE","customer_id","token_2_compute","network_id","TRON","address_id","addr_2",
                "address","T2"+"a".repeat(32),"purpose","CUSTOMER_DEPOSIT","status","ACTIVE","monitoring_ready",true)));
        fixture.jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=2");
        var test=batch(1,"READY","WAITING");test.putAll(fields("batch_id","test_batch","customer_id","token_2_compute","address_id","addr_2","address","T2"+"a".repeat(32)));
        assertEquals("REVIEW",apply(test));
        assertEquals("TEST_MEMBER_NOT_ALLOWED",fixture.data.one("SELECT * FROM compute_mcag_collection_batch WHERE batch_id='test_batch'").get("reason"));noUserAccounting();
    }
    @Test void concurrentDuplicateSummariesKeepOneBatchAndOneEvent() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            var calls=new ArrayList<Future<String>>();
            for(int i=0;i<4;i++)calls.add(pool.submit(()->fixture.tx.execute(tx->collections.apply(batch(1,"READY","WAITING"),event(1,1)))));
            for(var call:calls)assertEquals("TRACKED",call.get(20,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertEquals(1,count("compute_mcag_collection_batch"));assertEquals(1,count("compute_mcag_event"));noUserAccounting();
    }
    @Test void partialDetailOrForeignTenantCannotPersistTrackingState() {
        var partial=batch(1,"READY","WAITING");partial.put("items",List.of());
        assertThrows(BusinessException.class,()->apply(partial));
        var foreign=batch(1,"READY","WAITING");foreign.put("merchant_id","other");
        assertThrows(BusinessException.class,()->apply(foreign));assertEquals(0,count("compute_mcag_collection_batch"));noUserAccounting();
    }
}