package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.token.repository.compute.ComputeData.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 权威摘要查询桩：归集数量不影响请求数，不访问真实网关或数据库。 */
class ComputeMcagBatchReaderTest {
    private final ComputeMcagClient client=mock(ComputeMcagClient.class);
    private final ComputeMcagProperties config=configuration();

    static ComputeMcagProperties configuration() {
        var value=new ComputeMcagProperties();value.setMerchantId("mch_token");return value;
    }
    static Map<String,Object> summary(long count) {
        return fields("merchant_id","mch_token","environment","LIVE","batch_id","batch1","resource_version",3L,
                "customer_id","token_1_compute","address_id","addr_1","asset_id","USDT_TRON","network_id","TRON",
                "address","T1"+"a".repeat(32),"purpose","CUSTOMER_DEPOSIT","accounting_model","RECEIPT_SWEEP",
                "status","READY","credit_status","INDEPENDENT","sweep_status","WAITING","amount",Long.toString(count),
                "credited_amount",Long.toString(count-1),"uncredited_amount","1","receipt_count",count,"details_available",true);
    }
    static Map<String,Object> serviceFeeSummary() {
        var value=summary(1);value.put("purpose","SERVICE_FEE");value.put("customer_id","");return value;
    }
    static Map<String,Object> response(Map<String,Object> value) {return Map.of("items",List.of(value),"has_more",false);}

    @Test void enormousReceiptCountStillUsesOneSummaryRequestWithoutAllocatingReceipts() {
        var value=summary(Long.MAX_VALUE);
        when(client.request("collection-batches/query",Map.of("batch_id","batch1","view","summary"))).thenReturn(response(value));
        assertSame(value,ComputeMcagBatchReader.read(client,config,"batch1"));
        verify(client).request("collection-batches/query",Map.of("batch_id","batch1","view","summary"));
        verifyNoMoreInteractions(client);assertFalse(value.containsKey("items"));
    }
    @Test void serviceFeeHasEmptyCustomerWhileCustomerDepositRequiresIdentity() {
        when(client.request(anyString(),anyMap())).thenReturn(response(serviceFeeSummary()));
        assertEquals("",ComputeMcagBatchReader.read(client,config,"batch1").get("customer_id"));
        for(Object bad:List.of("", " ", 1)) {
            var value=summary(1);value.put("customer_id",bad);
            assertThrows(BusinessException.class,()->ComputeMcagBatchReader.validate(config,value));
        }
        var missing=serviceFeeSummary();missing.remove("customer_id");
        assertThrows(BusinessException.class,()->ComputeMcagBatchReader.validate(config,missing));
    }
    @Test void tenantBatchIdentityAndOneItemMustMatch() {
        for(String key:List.of("merchant_id","environment","batch_id")) {
            var value=summary(1);value.put(key,"other");
            when(client.request(anyString(),anyMap())).thenReturn(response(value));
            assertThrows(BusinessException.class,()->ComputeMcagBatchReader.read(client,config,"batch1"));
        }
        when(client.request(anyString(),anyMap())).thenReturn(Map.of("items",List.of(summary(1),summary(1))));
        assertThrows(BusinessException.class,()->ComputeMcagBatchReader.read(client,config,"batch1"));
    }
    @Test void summaryAmountsMustBeExactNonnegativeAndConserved() {
        for(Object amount:List.of("0","-1","1e3","NaN",1.0,"0.999")) {
            var value=summary(1);value.put("amount",amount);
            assertThrows(BusinessException.class,()->ComputeMcagBatchReader.validate(config,value));
        }
        var dust=summary(2);dust.putAll(fields("amount","0.123456789012345678901234567890","credited_amount","0.1","uncredited_amount","0.023456789012345678901234567890"));
        assertDoesNotThrow(()->ComputeMcagBatchReader.validate(config,dust));
        dust.put("uncredited_amount","-0.01");
        assertThrows(BusinessException.class,()->ComputeMcagBatchReader.validate(config,dust));
    }
    @Test void malformedVersionsCountsAndMissingStatesAreRejected() {
        for(String field:List.of("resource_version","receipt_count"))for(Object invalid:List.of(0,-1,"1.5","9223372036854775808",1.0)) {
            var value=summary(1);value.put(field,invalid);
            assertThrows(BusinessException.class,()->ComputeMcagBatchReader.validate(config,value));
        }
        for(String field:List.of("accounting_model","status","sweep_status","credit_status","details_available")) {
            var value=summary(1);value.remove(field);
            assertThrows(BusinessException.class,()->ComputeMcagBatchReader.validate(config,value));
        }
    }
    @Test void receiptPagesAreNeverConsumedAsAuthoritativeSummaries() {
        for(String field:List.of("items","receipt_has_more","receipt_next_cursor")) {
            var value=summary(6000);value.put(field,field.equals("items")?List.of():false);
            assertThrows(BusinessException.class,()->ComputeMcagBatchReader.validate(config,value));
        }
    }
}