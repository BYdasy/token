package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.service.compute.ComputePaymentGateway;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 数据库缓存与远程桩验证钱包隔离、状态时效和主地址切换，不访问真实网关。 */
@Tag("regression")
class ComputeMcagAddressCacheTest {
    ComputeMcagFixture fixture;
    ComputeMcagPaymentGateway gateway;
    ComputeMcagClient client;
    AtomicBoolean ready;
    AtomicReference<String> addressId;
    @BeforeEach void setup() throws Exception {
        fixture=new ComputeMcagFixture();fixture.setup();
        client=mock(ComputeMcagClient.class);ready=new AtomicBoolean(true);addressId=new AtomicReference<>("addr_1");
        var deposits=spy(fixture.deposits);
        doAnswer(call->fixture.tx.execute(s->{try{return call.callRealMethod();}catch(RuntimeException ex){throw ex;}catch(Throwable ex){throw new IllegalStateException(ex);}})).when(deposits).claimRefresh(anyLong(),anyString(),any(),anyString(),anyString());
        doAnswer(call->fixture.tx.execute(s->{try{return call.callRealMethod();}catch(RuntimeException ex){throw ex;}catch(Throwable ex){throw new IllegalStateException(ex);}})).when(deposits).bind(anyLong(),anyString(),any(),anyMap(),anyString());
        var channels=new ComputeMcagChannelService(fixture.data,fixture.config);
        gateway=new ComputeMcagPaymentGateway(fixture.config,client,deposits,new ComputeCurrencyService(fixture.data),fixture.data,mock(ComputeMcagPayoutService.class),
                mock(ComputeMcagCollectionService.class),mock(ComputeMcagReceiptService.class),channels);
        when(client.request(anyString(),anyMap())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"远程核验不能持有资金事务");
            String operation=call.getArgument(0);Map<String,Object> body=call.getArgument(1);
            if("assets/query".equals(operation))return fields("items",List.of(fields("asset_id","USDT_TRON","network_id","TRON","symbol","USDT","deposit_enabled",true)));
            assertEquals("customer-addresses/get-or-create",operation);
            String customer=text(body.get("customer_id"));boolean project=customer.matches(".*_project(?:_[1-9][0-9]*)?");
            boolean next=customer.matches(".*_(?:compute|project)_[1-9][0-9]*");
            return fields("merchant_id","mch_token","environment","LIVE","customer_id",customer,"network_id","TRON","purpose","CUSTOMER_DEPOSIT","status","ACTIVE",
                    "monitoring_ready",ready.get(),"address_id",next?"addr_"+customer:project?"addr_project":addressId.get(),
                    "address",next?"T_test_"+customer:project?"Tproject"+"a".repeat(27):"addr_1".equals(addressId.get())?"T1"+"a".repeat(32):"T2"+"b".repeat(32));
        });
    }
    private static final String EVM_ADDRESS="0x"+"a".repeat(40);
    private static final Map<String,String> EVM_NETWORKS=Map.of("ETH","ETHEREUM_MAINNET","BSC","BSC_MAINNET","POL","POLYGON_MAINNET");

    Map<String,Boolean> configureEvm() {
        var states=new HashMap<String,Boolean>();
        for(var entry:EVM_NETWORKS.entrySet()) {
            fixture.jdbc.update("INSERT INTO compute_currency(currency,network,enabled,deposit_enabled) VALUES('USDT',?,1,1)",entry.getKey());
            ComputeMcagTestSchema.seedMapping(fixture.jdbc,"USDT",entry.getKey(),"USDT_"+entry.getKey(),entry.getValue(),18);
            states.put(entry.getKey(),true);
        }
        TokenTestSchema.seedCurrencies(fixture.jdbc);
        fixture.jdbc.update("UPDATE compute_currency_network SET chain_type='EVM' WHERE network_code IN ('ETH','BSC','POL')");
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"远程核验不能持有资金事务");
            String operation=call.getArgument(0);Map<String,Object> body=call.getArgument(1);
            if("assets/query".equals(operation))return fields("items",EVM_NETWORKS.entrySet().stream().map(entry->fields(
                    "asset_id","USDT_"+entry.getKey(),"network_id",entry.getValue(),"symbol","USDT","deposit_enabled",true)).toList());
            assertEquals("customer-addresses/get-or-create",operation);
            String network=text(body.get("asset_id")).substring("USDT_".length());
            boolean monitoring=Objects.requireNonNull(states.get(network));
            return fields("merchant_id","mch_token","environment","LIVE","customer_id",body.get("customer_id"),
                    "network_id",EVM_NETWORKS.get(network),"purpose","CUSTOMER_DEPOSIT","status","ACTIVE",
                    "monitoring_ready",monitoring,
                    "address_id","evm_"+network,"address",EVM_ADDRESS,"memo","");
        }).when(client).request(anyString(),anyMap());
        return states;
    }
    Map<String,Object> getEvm(String network){return gateway.getOrCreateAddress(1,new ComputePaymentGateway.Context("USDT",network,"COMPUTE"));}
    Map<String,Object> get(String wallet){return gateway.getOrCreateAddress(1,new ComputePaymentGateway.Context("USDT","TRC20",wallet));}
    void expire(){fixture.jdbc.update("UPDATE compute_mcag_address_current SET verified_at=?",now().minusSeconds(61));}
    @Test void repeatReadsUseLocalDatabaseAndAssetsAreSharedAcrossWalletsButAddressesAreNot() {
        assertTrue(enabled(get("COMPUTE").get("monitoringReady")));get("COMPUTE");
        assertEquals("addr_project",get("PROJECT").get("addressId"));get("PROJECT");
        verify(client,times(1)).request(eq("assets/query"),anyMap());verify(client,times(2)).request(eq("customer-addresses/get-or-create"),anyMap());
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address_current",Integer.class));
        fixture.jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=1");assertThrows(BusinessException.class,()->get("COMPUTE"));
        fixture.jdbc.update("UPDATE compute_member SET enabled=1,is_test=1 WHERE id=1");assertThrows(BusinessException.class,()->get("COMPUTE"));
        verify(client,times(2)).request(eq("customer-addresses/get-or-create"),anyMap());
    }
    @Test void concurrentRequestsMergeAndMappingRevisionForcesRevalidation() throws Exception {
        var pool=Executors.newFixedThreadPool(6);
        try {var calls=new ArrayList<Future<Map<String,Object>>>();for(int i=0;i<8;i++)calls.add(pool.submit(()->get("COMPUTE")));
            for(var call:calls)assertEquals("addr_1",call.get(15,TimeUnit.SECONDS).get("addressId"));
        } finally {pool.shutdownNow();}
        verify(client,times(1)).request(eq("customer-addresses/get-or-create"),anyMap());
        fixture.jdbc.update("UPDATE compute_mcag_channel SET mapping_version=2");get("COMPUTE");verify(client,times(2)).request(eq("customer-addresses/get-or-create"),anyMap());
    }
    @Test void notReadyNeverExposesAddressAndRetiredPrimaryKeepsHistoricalOwnership() {
        ready.set(false);var pending=get("COMPUTE");assertEquals("ACTIVE",pending.get("status"));assertEquals("",pending.get("address"));assertFalse(enabled(pending.get("monitoringReady")));
        expire();ready.set(true);get("COMPUTE");expire();addressId.set("addr_rotated");assertEquals("addr_rotated",get("COMPUTE").get("addressId"));
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address WHERE customer_id='token_1_compute'",Integer.class));
        assertEquals("T1"+"a".repeat(32),fixture.jdbc.queryForObject("SELECT address FROM compute_mcag_address WHERE address_id='addr_1'",String.class));
    }
    @Test void expiredFailureCannotReuseOldSuccessAndAnotherInstanceLeaseNeverAllocatesAgain() {
        get("COMPUTE");expire();
        doThrow(new BusinessException(504,"server.compute.payment.timeout")).when(client).request(eq("customer-addresses/get-or-create"),anyMap());
        assertThrows(BusinessException.class,()->get("COMPUTE"));
        assertNull(fixture.data.one("SELECT * FROM compute_mcag_address_current").get("verifiedAt"));
        fixture.jdbc.update("UPDATE compute_mcag_address_current SET lease_token='another',lease_until=?",now().plusSeconds(30));
        assertEquals("server.compute.v15.addressPreparing",assertThrows(BusinessException.class,()->get("COMPUTE")).getMessage());
        verify(client,times(2)).request(eq("customer-addresses/get-or-create"),anyMap());
    }
    @Test void sameEvmAddressKeepsEthPolygonReadyWhileBscIsNotReady() {
        var states=configureEvm();states.put("BSC",false);
        var eth=getEvm("ETH");var bsc=getEvm("BSC");var polygon=getEvm("POL");
        assertEquals(EVM_ADDRESS,eth.get("address"));assertEquals(EVM_ADDRESS,polygon.get("address"));
        assertTrue(enabled(eth.get("monitoringReady")));assertTrue(enabled(polygon.get("monitoringReady")));
        assertEquals("ACTIVE",bsc.get("status"));
        assertEquals("",bsc.get("address"));assertEquals("",bsc.get("memo"));assertFalse(enabled(bsc.get("monitoringReady")));
        assertEquals(EVM_ADDRESS,getEvm("ETH").get("address"));
        assertFalse(enabled(getEvm("BSC").get("monitoringReady")));
        assertEquals(3,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address WHERE network IN ('ETH','BSC','POL') AND address=?",Integer.class,EVM_ADDRESS));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet",Integer.class));
    }

    @Test void bscBecomesUsableAfterItsOwnShortCacheExpiresWithoutNewIdentity() {
        var states=configureEvm();states.put("BSC",false);
        var preparing=getEvm("BSC");assertEquals("ACTIVE",preparing.get("status"));
        states.put("BSC",true);
        fixture.jdbc.update("UPDATE compute_mcag_address_current SET verified_at=? WHERE asset_id='USDT_BSC'",now().minusSeconds(3));
        var completed=getEvm("BSC");
        assertEquals("ACTIVE",completed.get("status"));assertTrue(enabled(completed.get("monitoringReady")));
        assertEquals(EVM_ADDRESS,completed.get("address"));assertEquals(preparing.get("addressId"),completed.get("addressId"));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address WHERE asset_id='USDT_BSC'",Integer.class));
        assertEquals(0L,fixture.jdbc.queryForObject("SELECT generation FROM compute_mcag_address_generation WHERE network_id='BSC_MAINNET'",Long.class));
        verify(client,times(2)).request(eq("customer-addresses/get-or-create"),argThat(body->"USDT_BSC".equals(body.get("asset_id"))&&"token_1_compute".equals(body.get("customer_id"))));
    }

    @Test void newEvmAddressesAreImmediatelyUsableWithoutInitializationFields() {
        configureEvm();
        for(String network:List.of("ETH","BSC","POL")) {
            var address=getEvm(network);
            assertEquals("ACTIVE",address.get("status"));assertTrue(enabled(address.get("monitoringReady")));
            assertEquals(EVM_ADDRESS,address.get("address"));
            assertFalse(address.containsKey("initializationStatus"));
            assertEquals(address,getEvm(network),"同一真实链命中原有短缓存，不分配第二个地址");
        }
        verify(client,times(3)).request(eq("customer-addresses/get-or-create"),anyMap());
        assertEquals(3,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address WHERE network IN ('ETH','BSC','POL')",Integer.class));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_deposit",Integer.class));
    }
}