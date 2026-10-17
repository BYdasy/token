package org.token.service.compute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离 H2 验证已授权充值页的精确归集关联；只读投影不调用网关或改变资金。 */
class ComputeDepositCollectionProjectionTest {
    private ComputeMcagReceiptTest receipt;
    private ComputeMcagFixture fixture;
    private Map<String,Object> order;

    @BeforeEach void setup() throws Exception {
        receipt=new ComputeMcagReceiptTest();receipt.setup();fixture=receipt.fixture;
        order=receipt.order();applyOrder();
    }

    @Test void exactSnapshotBatchConnectsModernReceiptWithoutLegacyReceiptIndex() {
        var batch=receipt.batch("RECEIPT_SWEEP");batch.put("sweep_status","SUCCEEDED");applyBatch(batch);
        
        // 同地址已有成功批次也不能代替这笔订单的权威关联。
        assertEquals("WAITING_BATCH",view().get("collectionReason"));
        order.put("batch_id","batch1");applyOrder();
        var row=view();
        assertEquals("COLLECTED",row.get("collectionState"));assertEquals("SUCCEEDED",row.get("collectionStatus"));
        assertEquals("batch1",row.get("collectionBatchId"));assertEquals("SINGLE_RECEIPT",row.get("accountingModel"));
        assertFalse(row.containsKey("merchantId"));assertFalse(row.containsKey("addressId"));
        assertUnchangedFunds(row);
    }

    @Test void noBatchAndKnownBatchAwaitingSyncHaveDifferentNeutralReasons() {
        var waiting=view();assertEquals("NOT_COLLECTED",waiting.get("collectionState"));
        assertEquals("WAITING_BATCH",waiting.get("collectionReason"));assertNull(waiting.get("collectionStatus"));
        order.put("batch_id","known-but-not-synced");applyOrder();
        var missing=view();assertEquals("UNKNOWN",missing.get("collectionState"));assertEquals("SYNC_PENDING",missing.get("collectionReason"));
        assertEquals(Map.of(),missing.get("collectionDetails"));assertUnchangedFunds(missing);
    }

    @Test void sameVersionRefreshProjectsAuthoritativeMinimumWithoutRecrediting() {
        Object originalVersion=order.get("resource_version");
        assertEquals("WAITING_BATCH",view().get("collectionReason"));
        order.put("collection_reason_code","BELOW_SWEEP_THRESHOLD");applyOrder();
        var waiting=view();assertEquals("NOT_COLLECTED",waiting.get("collectionState"));
        assertEquals("BELOW_SWEEP_THRESHOLD",waiting.get("collectionReason"));assertNull(waiting.get("collectionStatus"));
        assertEquals("BELOW_SWEEP_THRESHOLD",fixture.data.jsonMap(fixture.data.one("SELECT snapshot_json FROM compute_mcag_deposit_order").get("snapshotJson")).get("collection_reason_code"));
        assertEquals(originalVersion,order.get("resource_version"));assertUnchangedFunds(waiting);
        // 门槛或可归集金额变化时，同版本的权威查询必须清除旧原因，不能再次入账。
        order.put("collection_reason_code",null);applyOrder();
        assertEquals("WAITING_BATCH",view().get("collectionReason"));assertUnchangedFunds(view());
        order.put("collection_reason_code","BELOW_SWEEP_THRESHOLD");applyOrder();
        order.remove("collection_reason_code");applyOrder();
        assertEquals("WAITING_BATCH",view().get("collectionReason"));assertUnchangedFunds(view());
    }

    @Test void explicitBatchAndMissingSnapshotOutrankAnOldMinimumReason() {
        order.put("collection_reason_code","BELOW_SWEEP_THRESHOLD");order.put("batch_id","batch1");applyOrder();
        assertEquals("SYNC_PENDING",view().get("collectionReason"));
        var batch=receipt.batch("RECEIPT_SWEEP");batch.put("sweep_status","SUCCEEDED");applyBatch(batch);
        assertEquals("COLLECTED",view().get("collectionState"));assertEquals("",view().get("collectionReason"));
        var stored=new LinkedHashMap<>(fixture.data.jsonMap(fixture.data.one("SELECT snapshot_json FROM compute_mcag_deposit_order").get("snapshotJson")));
        stored.remove("batch_id");fixture.jdbc.update("UPDATE compute_mcag_deposit_order SET snapshot_json=?",fixture.data.json(stored));
        assertEquals("UNKNOWN",view().get("collectionState"));assertEquals("SYNC_PENDING",view().get("collectionReason"));
        assertUnchangedFunds(view());
    }

    @Test void unknownReadinessReasonDoesNotGuessThresholdFromAmount() {
        order.put("collection_reason_code","FUTURE_REASON");applyOrder();
        assertEquals("NOT_COLLECTED",view().get("collectionState"));assertEquals("WAITING_BATCH",view().get("collectionReason"));
        assertUnchangedFunds(view());
    }

    @Test void batchReasonSurvivesBeforeExecutionAndTakesPriorityOverOperationReason() {
        order.put("batch_id","batch1");applyOrder();
        var batch=receipt.batch("RECEIPT_SWEEP");batch.remove("collection");batch.put("reason_code","SERVICE_FEE_PENDING");applyBatch(batch);
        assertEquals("SERVICE_FEE_PENDING",view().get("collectionReason"));
        var stored=fixture.data.one("SELECT collection_json FROM compute_mcag_collection_batch");
        assertEquals("SERVICE_FEE_PENDING",fixture.data.jsonMap(stored.get("collectionJson")).get("batch_reason_code"));
        batch.putAll(fields("resource_version",2,"reason_code","FEE_LIMIT_EXCEEDED","collection",fields("reason_code","INSUFFICIENT_NATIVE_GAS")));
        applyBatch(batch);assertEquals("FEE_LIMIT_EXCEEDED",view().get("collectionReason"));
        batch.putAll(fields("resource_version",3,"sweep_status","SUCCEEDED","reason_code",null,"collection",Map.of()));
        applyBatch(batch);var row=view();assertEquals("",row.get("collectionReason"));assertEquals("COLLECTED",row.get("collectionState"));
        assertUnchangedFunds(row);
    }

    @Test void failedCollectionNeverChangesCreditedDepositOrLedger() {
        order.put("batch_id","batch1");applyOrder();
        var batch=receipt.batch("RECEIPT_SWEEP");batch.putAll(fields("sweep_status","FAILED","reason_code","INSUFFICIENT_NATIVE_GAS"));applyBatch(batch);
        var row=view();assertEquals("NOT_COLLECTED",row.get("collectionState"));assertEquals("FAILED",row.get("collectionStatus"));
        assertEquals("INSUFFICIENT_NATIVE_GAS",row.get("collectionReason"));assertUnchangedFunds(row);
    }

    @Test void wrongTenantOrBatchIdentityCannotSupplySuccessForAnotherOrder() {
        order.put("batch_id","batch1");applyOrder();
        var batch=receipt.batch("RECEIPT_SWEEP");batch.put("sweep_status","SUCCEEDED");applyBatch(batch);
        fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET merchant_id='other-merchant'");
        assertEquals("SYNC_PENDING",view().get("collectionReason"));
        fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET merchant_id='mch_token'");
        fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET environment='SANDBOX'");
        assertEquals("SYNC_PENDING",view().get("collectionReason"));
        fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET environment='LIVE'");
        for(var field:Map.of("customer_id","token_1_compute","address_id","addr_1","asset_id","USDT_TRON","network_id","TRON").entrySet()) {
            fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET "+field.getKey()+"='unrelated'");
            var row=view();assertEquals("UNKNOWN",row.get("collectionState"));assertEquals("LINK_CONFLICT",row.get("collectionReason"));
            assertEquals(Map.of(),row.get("collectionDetails"));assertNull(row.get("collectionStatus"));
            fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET "+field.getKey()+"=?",field.getValue());
        }
        fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET collection_json=?","{\"purpose\":\"SERVICE_FEE\"}");
        assertEquals("LINK_CONFLICT",view().get("collectionReason"));assertUnchangedFunds(view());
    }

    @Test void missingSnapshotUnknownSweepAndPublicUnknownReasonRemainExplicit() {
        var old=new LinkedHashMap<>(fixture.data.jsonMap(fixture.data.one("SELECT snapshot_json FROM compute_mcag_deposit_order").get("snapshotJson")));
        old.remove("batch_id");fixture.jdbc.update("UPDATE compute_mcag_deposit_order SET snapshot_json=?",fixture.data.json(old));
        assertEquals("UNKNOWN",view().get("collectionState"));assertEquals("SYNC_PENDING",view().get("collectionReason"));
        order.put("batch_id","batch1");applyOrder();
        var batch=receipt.batch("RECEIPT_SWEEP");applyBatch(batch);
        fixture.jdbc.update("UPDATE compute_mcag_collection_batch SET sweep_status='FUTURE_STATE',collection_json=?",
                "{\"purpose\":\"CUSTOMER_DEPOSIT\",\"batch_reason_code\":\"FUTURE_REASON\"}");
        var row=view();assertEquals("UNKNOWN",row.get("collectionState"));assertEquals("FUTURE_STATE",row.get("collectionStatus"));
        assertEquals("FUTURE_REASON",row.get("collectionReason"));assertUnchangedFunds(row);
    }

    @Test void projectionOnlyEnrichesRequestedPageAndManualDepositsHaveNoCollectionClaim() {
        var local=fixture.data.one("SELECT * FROM compute_deposit");
        var manual=fields("id",999,"memberId",1,"recordSource","ADMIN_MANUAL","status","APPROVED","network","TRC20");
        ComputePaymentRecords.enrich(fixture.data,"deposits",List.of(manual),true);
        assertFalse(manual.containsKey("collectionState"));
        assertEquals(1,count("compute_deposit"));assertUnchangedFunds(local);
    }

    private void applyOrder() {fixture.tx.executeWithoutResult(tx->receipt.service.apply(order,null));}
    private void applyBatch(Map<String,Object> batch) {fixture.tx.executeWithoutResult(tx->receipt.collections.apply(batch,null));}
    private Map<String,Object> view() {var rows=fixture.data.rows("SELECT * FROM compute_deposit");ComputePaymentRecords.enrich(fixture.data,"deposits",rows,true);return rows.get(0);}
    private int count(String table) {return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private void assertUnchangedFunds(Map<String,Object> row) {
        assertEquals("APPROVED",row.get("status"));assertEquals("MCAG_VERIFIED",row.get("paymentStatus"));
        assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));assertEquals(1,count("compute_finance_snapshot"));
        assertEquals(0,new BigDecimal("12.12345678").compareTo(fixture.jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type='COMPUTE'",BigDecimal.class)));
    }
}
