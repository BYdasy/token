package org.token.service.compute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeMcagDepositRepository;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 全量会员清理之后的迟到回调：保留永久归属，不重建会员业务记录。 */
class ComputeMcagRemovedMemberTest {
    private ComputeMcagFixture fixture;
    private ComputeMcagReceiptService receipts;
    private ComputeMcagCollectionService collections;

    @BeforeEach void setup() throws Exception {
        fixture=new ComputeMcagFixture();fixture.setup();
        services(fixture.deposits);
    }

    private void services(ComputeMcagDepositService deposits) {
        receipts=new ComputeMcagReceiptService(fixture.data,fixture.business,fixture.config,deposits,new ComputeMcagChannelService(fixture.data,fixture.config));
        collections=new ComputeMcagCollectionService(fixture.data,fixture.config,deposits);
    }

    private Map<String,Object> order() {
        return fields("merchant_id","mch_token","environment","LIVE","order_id","removed_order","receipt_id","removed_receipt","transfer_event_id","removed_transfer",
                "customer_id","token_1_compute","asset_id","USDT_TRON","network_id","TRON","address_id","addr_1","address","T1"+"a".repeat(32),
                "purpose","CUSTOMER_DEPOSIT","accounting_model","SINGLE_RECEIPT","status","CONFIRMED","resource_version",1,"amount","12.12345678",
                "tx_hash","removed_hash","transfer_locator","log:1","chain_status","CONFIRMED","current_confirmations",25,"required_confirmations",20,
                "finality_satisfied",true,"risk_status","CLEAR","credit_decision","CREDITED","decimals",8);
    }

    private Map<String,Object> batch() { return ComputeMcagBatchReaderTest.summary(2); }
    private Map<String,Object> event(boolean collection) {
        return fields("merchant_id","mch_token","environment","LIVE","event_id",collection?"removed_batch_event":"removed_order_event","event_sequence",collection?2:1,
                "resource_type",collection?"COLLECTION_BATCH":"DEPOSIT_ORDER","resource_id",collection?"batch1":"removed_order","resource_version",collection?3:1,
                "event_type",collection?"collection.created":"deposit.confirmed");
    }
    private String receipt(Map<String,Object> value) {return fixture.tx.execute(tx->receipts.apply(value,null));}
    private String collection(Map<String,Object> value) {return fixture.tx.execute(tx->collections.apply(value,null));}
    private int count(String table) {return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private void removeMember() {fixture.jdbc.update("DELETE FROM compute_member WHERE id=1");fixture.jdbc.update("DELETE FROM compute_mcag_address_current");}
    private void noOrdersOrFunds() {
        for(String table:List.of("compute_deposit","compute_mcag_deposit_order","compute_mcag_collection_batch","compute_wallet","compute_ledger","compute_finance_snapshot"))
            assertEquals(0,count(table),table);
        verifyNoInteractions(fixture.messages);
    }

    @Test void repeatedLateCallbacksOnlyRegisterIgnoredEvents() {
        removeMember();
        for(int attempt=0;attempt<2;attempt++) {
            assertEquals("IGNORED",fixture.tx.execute(tx->receipts.apply(order(),event(false))));
            assertEquals("IGNORED",fixture.tx.execute(tx->collections.apply(batch(),event(true))));
        }
        assertEquals(2,count("compute_mcag_event"));
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_event WHERE state='IGNORED'",Integer.class));
        assertEquals(1,count("compute_mcag_address"));assertEquals(1,count("compute_mcag_customer_identity"));
        noOrdersOrFunds();
    }

    @Test void registeredCustomerCanHaveAnAddressReturnedAfterMemberCleanup() {
        removeMember();
        var receipt=order();receipt.putAll(fields("address_id","late_address","address","T9"+"b".repeat(32)));
        var collection=batch();collection.putAll(fields("address_id","late_address","address","T9"+"b".repeat(32)));
        assertEquals("IGNORED",receipt(receipt));assertEquals("IGNORED",collection(collection));
        assertEquals(1,count("compute_mcag_address"));noOrdersOrFunds();
    }

    @Test void wrongTenantOrConflictingRegisteredAddressStillFails() {
        removeMember();
        for(String key:List.of("merchant_id","environment","network_id","address")) {
            var receipt=order();receipt.put(key,"changed");
            var collection=batch();collection.put(key,"changed");
            assertThrows(BusinessException.class,()->receipt(receipt),key);
            assertThrows(BusinessException.class,()->collection(collection),key);
        }
        var wrongEvent=event(false);wrongEvent.put("resource_id","other_order");
        assertThrows(BusinessException.class,()->fixture.tx.execute(tx->receipts.apply(order(),wrongEvent)));
        assertEquals(0,count("compute_mcag_event"));noOrdersOrFunds();
    }

    @Test void unregisteredCustomerRemainsReviewableAfterCleanup() {
        removeMember();
        var receipt=order();receipt.putAll(fields("customer_id","token_3_compute","address_id","unknown_address"));
        var collection=batch();collection.putAll(fields("customer_id","token_3_compute","address_id","unknown_address"));
        assertEquals("REVIEW",receipt(receipt));assertEquals("REVIEW",collection(collection));
        assertEquals("UNBOUND_ADDRESS",fixture.data.one("SELECT reason FROM compute_mcag_deposit_order").get("reason"));
        assertEquals("UNBOUND_ADDRESS",fixture.data.one("SELECT reason FROM compute_mcag_collection_batch").get("reason"));
        assertEquals(0,count("compute_deposit"));assertEquals(0,count("compute_ledger"));
    }

    @Test void disabledMemberIsNotTreatedAsRemoved() {
        fixture.jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=1");
        assertEquals("CREDITED",receipt(order()));
        assertEquals("TRACKED",collection(batch()));
        assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));
    }

    @Test void oldCallbacksCannotCreateOrdersForNewMember() {
        removeMember();
        fixture.jdbc.update("INSERT INTO compute_member(id,username,password_hash,invite_code,is_test,created_at,updated_at) VALUES(3,'new_member','unused','new_invite',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        fixture.tx.executeWithoutResult(tx->fixture.deposits.bind(3,"COMPUTE",fixture.config.getChannels().get("usdt-tron"),fields(
                "merchant_id","mch_token","environment","LIVE","customer_id","token_3_compute","network_id","TRON","address_id","addr_3",
                "address","T3"+"a".repeat(32),"purpose","CUSTOMER_DEPOSIT","status","ACTIVE","monitoring_ready",true)));
        assertEquals("IGNORED",receipt(order()));assertEquals("IGNORED",collection(batch()));
        var current=order();current.putAll(fields("customer_id","token_3_compute","address_id","addr_3","address","T3"+"a".repeat(32),
                "order_id","new_order","receipt_id","new_receipt","transfer_event_id","new_transfer","tx_hash","new_hash"));
        assertEquals("CREDITED",receipt(current));assertEquals("IGNORED",receipt(order()));
        assertEquals(1,count("compute_deposit"));assertEquals(1,count("compute_ledger"));
        assertEquals(3L,number(fixture.data.one("SELECT member_id FROM compute_deposit").get("memberId")));
        assertEquals(0,new BigDecimal("12.12345678").compareTo(fixture.jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=3 AND wallet_type='COMPUTE'",BigDecimal.class)));
    }

    @Test void memberLookupFailureCannotMasqueradeAsRemovedOrAcknowledgeEvent() {
        var repository=spy(new ComputeMcagDepositRepository(fixture.data));
        doThrow(new DataAccessResourceFailureException("isolated lookup failure")).when(repository).registeredMemberExists(1);
        services(new ComputeMcagDepositService(fixture.data,fixture.config,repository));
        assertThrows(DataAccessResourceFailureException.class,()->fixture.tx.execute(tx->receipts.apply(order(),event(false))));
        assertThrows(DataAccessResourceFailureException.class,()->fixture.tx.execute(tx->collections.apply(batch(),event(true))));
        assertEquals(0,count("compute_mcag_event"));noOrdersOrFunds();
    }
}
