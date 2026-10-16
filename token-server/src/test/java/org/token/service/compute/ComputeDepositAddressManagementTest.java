package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.controller.admin.ComputeDepositAddressController;
import org.token.service.compute.ComputeAgentScope;
import org.junit.jupiter.api.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.*;
import java.util.concurrent.*;
import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2及远程桩验证停用、代次、并发与代理范围，绝不访问真实网关。 */
@Tag("regression")
class ComputeDepositAddressManagementTest {
    ComputeMcagAddressCacheTest cache;
    ComputeMcagFixture fixture;
    ComputeAgentScope scope;
    ComputeDepositAddressManagementService management;
    @BeforeEach void setup() throws Exception {
        cache=new ComputeMcagAddressCacheTest();cache.setup();fixture=cache.fixture;
        scope=mock(ComputeAgentScope.class);when(scope.relations()).thenReturn(mock(ComputeMemberRelations.class));
        when(scope.currentAdminUid()).thenReturn(7);when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(true,List.of()));
        management=new ComputeDepositAddressManagementService(fixture.data,scope,fixture.config,new ComputeMcagChannelService(fixture.data,fixture.config),
                new ComputeCurrencyService(fixture.data),cache.gateway,fixture.tx.getTransactionManager());
    }
    long currentId() { return fixture.jdbc.queryForObject("SELECT MAX(id) FROM compute_mcag_address WHERE wallet_type='COMPUTE'",Long.class); }
    long generation() { return fixture.jdbc.queryForObject("SELECT generation FROM compute_mcag_address_generation WHERE member_id=1 AND wallet_type='COMPUTE' AND network_id='TRON'",Long.class); }

    @Test void reuseDisabledGenerationAndHistoryRemainStable() {
        management.generate("mcag_test","TRC20","COMPUTE");management.generate("mcag_test","TRC20","COMPUTE");
        verify(cache.client,times(1)).request(eq("customer-addresses/get-or-create"),anyMap());
        long first=currentId();management.disable(first);management.disable(first);
        assertEquals(1,generation());assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address WHERE disabled_at IS NOT NULL",Integer.class));
        assertEquals("addr_token_1_compute_1",cache.get("COMPUTE").get("addressId"));
        assertEquals("addr_project",cache.get("PROJECT").get("addressId"));
        assertEquals("token_1_compute_1",fixture.deposits.currentCustomer(1,"COMPUTE",fixture.config.getChannels().get("usdt-tron")));
        assertEquals("COMPUTE",fixture.deposits.customerIdentity("token_1_compute_1").get("walletType"));
        assertNull(fixture.deposits.customerIdentity("token_1_compute_999"));
        management.disable(currentId());assertEquals(2,generation());
        assertEquals("addr_token_1_compute_2",cache.get("COMPUTE").get("addressId"));
        assertEquals(4,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address",Integer.class));
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address WHERE disabled_at IS NOT NULL",Integer.class));
    }

    @Test void disabledGenerationConcurrentRequestsCreateOneAddressAndOfflineListingNeedsNoGateway() throws Exception {
        cache.get("COMPUTE");management.disable(currentId());
        var pool=Executors.newFixedThreadPool(6);
        try {
            var calls=new ArrayList<Future<Map<String,Object>>>();
            for(int i=0;i<8;i++)calls.add(pool.submit(()->cache.get("COMPUTE")));
            for(var call:calls)assertEquals("addr_token_1_compute_1",call.get(15,TimeUnit.SECONDS).get("addressId"));
        } finally { pool.shutdownNow(); }
        verify(cache.client,times(2)).request(eq("customer-addresses/get-or-create"),anyMap());
        clearInvocations(cache.client);
        assertEquals(2L,management.page(1,1,"mcag_test","TRC20",null).get("total"));
        assertEquals(1L,management.page(1,20,"mcag_test","TRC20","DISABLED").get("total"));
        verifyNoInteractions(cache.client);
    }

    @Test void lateOldGenerationResponseCannotReviveDisabledAddress() throws Exception {
        cache.get("COMPUTE");cache.expire();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{
            entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));
            return fields("merchant_id","mch_token","environment","LIVE","customer_id","token_1_compute","network_id","TRON","purpose","CUSTOMER_DEPOSIT", "status","ACTIVE","monitoring_ready",true,"address_id","addr_1","address","T1"+"a".repeat(32));
        }).when(cache.client).request(eq("customer-addresses/get-or-create"),anyMap());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var request=pool.submit(()->cache.get("COMPUTE"));assertTrue(entered.await(10,TimeUnit.SECONDS));
            management.disable(currentId());release.countDown();
            assertInstanceOf(BusinessException.class,assertThrows(ExecutionException.class,()->request.get(10,TimeUnit.SECONDS)).getCause());
        } finally { release.countDown();pool.shutdownNow(); }
        assertEquals(1,generation());
        assertEquals("DISABLED",fixture.data.one("SELECT * FROM compute_mcag_address_current").get("addressStatus"));
        assertNull(fixture.deposits.cached(1,"COMPUTE",fixture.config.getChannels().get("usdt-tron")));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address",Integer.class));
    }

    @Test void scopeAndLeafPermissionsProtectQueriesAndMutations() throws Exception {
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(2L)));
        assertEquals(0L,management.page(1,20,null,null,null).get("total"));
        doThrow(ComputeAgentScope.denied()).when(scope).assertMember(1);
        assertThrows(BusinessException.class,()->management.disable(currentId()));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address WHERE disabled_at IS NOT NULL",Integer.class));
        doThrow(ComputeAgentScope.denied()).when(scope).assertActiveMember(1);
        assertThrows(BusinessException.class,()->management.generate("mcag_test","TRC20","COMPUTE"));verifyNoInteractions(cache.client);
        assertEquals("(hasAnyAuthority('610114','super')) or (hasAnyAuthority('20400'))",ComputeDepositAddressController.class.getMethod("page",int.class,int.class,String.class,String.class,String.class,String.class,String.class).getAnnotation(PreAuthorize.class).value());
        assertEquals("(hasAnyAuthority('610114','super')) or (hasAnyAuthority('20400'))",ComputeDepositAddressController.class.getMethod("filterOptions").getAnnotation(PreAuthorize.class).value());
        assertEquals("(hasAnyAuthority('610115','super')) or (hasAnyAuthority('20401'))",ComputeDepositAddressController.class.getMethod("generate",Map.class).getAnnotation(PreAuthorize.class).value());
        assertEquals("(hasAnyAuthority('610116','super')) or (hasAnyAuthority('20402'))",ComputeDepositAddressController.class.getMethod("disable",long.class).getAnnotation(PreAuthorize.class).value());
    }

    @Test void networkGenerationIsSharedByCurrenciesAndLateCurrencyReceiptsInheritDisabledOwnership() {
        var another=new org.token.config.compute.ComputeMcagProperties.Channel();
        another.setCurrency("USDC");another.setNetwork("TRC20");another.setNetworkId("TRON");another.setAssetId("USDC_TRON");
        fixture.tx.executeWithoutResult(ignored->fixture.deposits.bind(1,"COMPUTE",another,fields(
                "merchant_id","mch_token","environment","LIVE","customer_id","token_1_compute","network_id","TRON","purpose","CUSTOMER_DEPOSIT",
                "status","ACTIVE","monitoring_ready",true,"address_id","addr_1","address","T1"+"a".repeat(32))));
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address",Integer.class));
        assertEquals(1L,management.page(1,1,"mcag_test","TRC20",null).get("total"));
        assertEquals(1,((List<?>)management.page(1,1,"mcag_test","TRC20",null).get("records")).size());
        assertEquals(0,((List<?>)management.page(2,1,"mcag_test","TRC20",null).get("records")).size());
        management.disable(currentId());
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address WHERE disabled_at IS NOT NULL",Integer.class));
        assertEquals("token_1_compute_1",fixture.deposits.currentCustomer(1,"COMPUTE",another));
        assertEquals("token_1_compute_1",fixture.deposits.currentCustomer(1,"COMPUTE",fixture.config.getChannels().get("usdt-tron")));
        another.setAssetId("TEST_TRON");another.setCurrency("TEST");
        long late=fixture.tx.execute(ignored->fixture.deposits.rememberBinding(1,"COMPUTE",another,"addr_1","T1"+"a".repeat(32),"","token_1_compute"));
        assertNotNull(fixture.data.one("SELECT disabled_at FROM compute_mcag_address WHERE id=?",late).get("disabledAt"));
        assertEquals(1L,management.page(1,20,"mcag_test","TRC20","DISABLED").get("total"));
        another.setNetworkId("ETHEREUM_MAINNET");
        assertEquals("token_1_compute",fixture.deposits.currentCustomer(1,"COMPUTE",another));
    }

    @Test void receiptDiscoveredAddressWithoutDisplayPointerCanBeDisabledAndReplaced() {
        fixture.jdbc.update("DELETE FROM compute_mcag_address_current");
        management.disable(currentId());
        assertEquals(1,generation());
        assertEquals("addr_token_1_compute_1",cache.get("COMPUTE").get("addressId"));
        assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address",Integer.class));
    }

    @Test void networkRowsChooseCurrentPointerBeforeStatusFilteringAndLocalNetworkAliasesStillMatch() {
        long original=currentId();
        seedHistory(1,"COMPUTE","TRON","TRON_ALIAS","T1"+"a".repeat(32),"","token_1_compute");
        var active=management.page(1,20,"mcag_test","TRON_ALIAS","ACTIVE");
        assertEquals(1L,active.get("total"));
        var selected=(Map<?,?>)((List<?>)active.get("records")).get(0);
        assertEquals(original,number(selected.get("id")),"币种历史别名不能覆盖当前已核验指针");
        assertFalse(selected.containsKey("currency"));
        assertEquals(0L,management.page(1,20,"mcag_test",null,"HISTORY").get("total"),"同地址历史别名不再重复成为历史行");
        fixture.jdbc.update("UPDATE compute_mcag_address_current SET address_status='UNAVAILABLE' WHERE binding_id=?",original);
        assertEquals(0L,management.page(1,20,"mcag_test",null,"ACTIVE").get("total"));
        assertEquals(1L,management.page(1,20,"mcag_test",null,"UNAVAILABLE").get("total"));
        verifyNoInteractions(cache.client);
    }

    @Test void literalAddressSearchEscapesWildcardsAndFiltersBeforePagination() {
        long first=seedHistory(1,"COMPUTE","TRON","TRC20","Tneedle%_!\\tail","","token_1_compute");
        long second=seedHistory(1,"COMPUTE","TRON","TRC20","TneedlePlain","","token_1_compute");
        seedHistory(1,"COMPUTE","TRON","TRC20","Tunrelated","","token_1_compute");
        for(String literal:List.of("%","_","!\\","%_!\\")) {
            var result=management.page(1,20,null,null,null,literal,null);
            assertEquals(1L,result.get("total"));
            assertEquals(first,number(((Map<?,?>)((List<?>)result.get("records")).get(0)).get("id")));
        }
        var page1=management.page(1,1,null,null,null," needle ",null);
        var page2=management.page(2,1,null,null,null,"needle",null);
        assertEquals(2L,page1.get("total"));assertEquals(2L,page2.get("total"));
        assertEquals(second,number(((Map<?,?>)((List<?>)page1.get("records")).get(0)).get("id")));
        assertEquals(first,number(((Map<?,?>)((List<?>)page2.get("records")).get(0)).get("id")));
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(2L)));
        assertEquals(0L,management.page(1,20,null,null,null,"needle",null).get("total"));
        assertThrows(BusinessException.class,()->management.page(1,20,null,null,null,"a".repeat(201),null));
        verifyNoInteractions(cache.client);
    }

    @Test void currencyAndNetworkMustMatchOneBindingWithoutReplacingTheCurrentPointer() {
        long original=currentId();
        String address="T1"+"a".repeat(32);
        seedHistory(1,"COMPUTE","TRON","TRON_ALIAS",address,"","token_1_compute");
        var currencyOnly=management.page(1,20,null,null,"ACTIVE",null,"USDC");
        assertEquals(1L,currencyOnly.get("total"));
        assertEquals(original,number(((Map<?,?>)((List<?>)currencyOnly.get("records")).get(0)).get("id")),"命中历史币种仍展示当前核验指针");
        assertEquals(1L,management.page(1,20,"mcag_test","TRON_ALIAS","ACTIVE","T1","USDC").get("total"));
        assertEquals(0L,management.page(1,20,null,"TRON_ALIAS",null,null,"USDT").get("total"));
        assertEquals(0L,management.page(1,20,null,"TRC20",null,null,"USDC").get("total"));
        assertEquals(0L,management.page(1,20,null,null,"HISTORY",null,"USDC").get("total"));
        assertEquals(0L,management.page(1,20,null,null,null,null,"UNKNOWN").get("total"));
        assertThrows(BusinessException.class,()->management.page(1,20,null,null,null,null,"A".repeat(33)));
        verifyNoInteractions(cache.client);
    }

    @Test void evmCurrencyAndRealNetworkFiltersDoNotCrossMatchDifferentBindings() {
        String address="0x"+"a".repeat(40);
        seedHistory(1,"COMPUTE","ETHEREUM_MAINNET","ETH",address,"","token_1_compute");
        long bsc=seedHistory(1,"COMPUTE","BSC_MAINNET","BSC",address,"","token_1_compute");
        fixture.jdbc.update("UPDATE compute_mcag_address SET currency='USDT' WHERE id=?",bsc);
        assertEquals(1L,management.page(1,20,null,"EVM",null,"aaaa","USDC").get("total"));
        assertEquals(1L,management.page(1,20,null,"BSC",null,null,"USDT").get("total"));
        assertEquals(0L,management.page(1,20,null,"ETH",null,null,"USDT").get("total"));
        assertEquals(0L,management.page(1,20,null,"BSC",null,null,"USDC").get("total"));
        assertEquals(1L,management.page(1,20,null,"ETH",null,null,null).get("total"));
        verifyNoInteractions(cache.client);
    }

    @Test void filterOptionsRetainDisabledHistoricalPairsAndRespectMerchantEnvironmentAndMemberScope() {
        long old=seedHistory(1,"COMPUTE","OLD_CHAIN","OLD_NET","old-address","","token_1_compute");
        fixture.jdbc.update("UPDATE compute_mcag_address SET currency='OLD',disabled_at=CURRENT_TIMESTAMP WHERE id=?",old);
        seedHistory(1,"COMPUTE","ETHEREUM_MAINNET","ETH","0x"+"a".repeat(40),"","token_1_compute");
        long other=seedHistory(2,"COMPUTE","OTHER_CHAIN","OTHER_NET","other-address","","token_2_compute");
        fixture.jdbc.update("UPDATE compute_mcag_address SET currency='OTHER' WHERE id=?",other);
        long merchant=seedHistory(1,"COMPUTE","HIDDEN_M","HIDDEN_M","merchant-address","","token_1_compute");
        long environment=seedHistory(1,"COMPUTE","HIDDEN_E","HIDDEN_E","sandbox-address","","token_1_compute");
        fixture.jdbc.update("UPDATE compute_mcag_address SET merchant_id='another' WHERE id=?",merchant);
        fixture.jdbc.update("UPDATE compute_mcag_address SET environment='SANDBOX' WHERE id=?",environment);
        fixture.config.getChannels().clear();fixture.jdbc.update("UPDATE compute_currency SET enabled=0");
        assertEquals(Set.of(fields("currency","USDT","network","TRC20"),fields("currency","OLD","network","OLD_NET"),
                fields("currency","USDC","network","ETH"),fields("currency","USDC","network","EVM"),
                fields("currency","OTHER","network","OTHER_NET")),new HashSet<>(management.filterOptions()));
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(2L)));
        assertEquals(List.of(fields("currency","OTHER","network","OTHER_NET")),management.filterOptions());
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of()));
        assertTrue(management.filterOptions().isEmpty());
        verifyNoInteractions(cache.client);
    }

    @Test void networkGroupingNormalizesOnlyEvmAndPreservesMemoWalletOwnerAddressAndGeneration() {
        String lower="0x"+"a".repeat(40),upper="0x"+"A".repeat(40);
        seedHistory(1,"COMPUTE","ETHEREUM_MAINNET","ETH",lower,"","token_1_compute");
        seedHistory(1,"COMPUTE","ETHEREUM_MAINNET","ETH",upper,"","token_1_compute");
        assertEquals(2L,management.page(1,20,null,null,null).get("total"));
        seedHistory(1,"COMPUTE","ETHEREUM_MAINNET","ETH",lower,"MemoA","token_1_compute");
        seedHistory(1,"COMPUTE","ETHEREUM_MAINNET","ETH",lower,"Memoa","token_1_compute");
        seedHistory(1,"COMPUTE","ETHEREUM_MAINNET","ETH","0x"+"b".repeat(40),"","token_1_compute");
        seedHistory(1,"COMPUTE","BSC_MAINNET","BSC",lower,"","token_1_compute");
        seedHistory(1,"PROJECT","ETHEREUM_MAINNET","ETH",lower,"","token_1_project");
        seedHistory(2,"COMPUTE","ETHEREUM_MAINNET","ETH",lower,"","token_2_compute");
        seedHistory(1,"COMPUTE","ETHEREUM_MAINNET","ETH",lower,"","token_1_compute_1");
        seedHistory(1,"COMPUTE","TRON","TRC20","T1"+"A".repeat(32),"","token_1_compute");
        long otherMerchant=seedHistory(1,"COMPUTE","ETHEREUM_MAINNET","ETH",lower,"","token_1_compute");
        fixture.jdbc.update("UPDATE compute_mcag_address SET merchant_id='other_merchant' WHERE id=?",otherMerchant);
        long otherEnvironment=seedHistory(1,"COMPUTE","ETHEREUM_MAINNET","ETH",lower,"","token_1_compute");
        fixture.jdbc.update("UPDATE compute_mcag_address SET environment='SANDBOX' WHERE id=?",otherEnvironment);
        var first=management.page(1,3,null,null,null);
        var second=management.page(2,3,null,null,null);
        assertEquals(9L,first.get("total"));assertEquals(first.get("total"),second.get("total"));
        var firstRows=(List<?>)first.get("records");
        var secondRows=(List<?>)second.get("records");
        assertEquals(3,firstRows.size());assertEquals(3,secondRows.size());
        var firstIds=firstRows.stream().map(row->number(((Map<?,?>)row).get("id"))).toList();
        assertTrue(secondRows.stream().noneMatch(row->firstIds.contains(number(((Map<?,?>)row).get("id")))));
        assertEquals(8L,management.page(1,20,null,null,"HISTORY").get("total"));
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(2L)));
        assertEquals(1L,management.page(1,20,null,null,null).get("total"),"归并前先限制代理用户范围");
        verifyNoInteractions(cache.client);
    }

    private long seedHistory(long member,String wallet,String networkId,String network,String address,String memo,String customer) {
        String key=UUID.randomUUID().toString();
        return fixture.data.insert("compute_mcag_address",fields("merchant_id","mch_token","environment","LIVE","member_id",member,
                "wallet_type",wallet,"customer_id",customer,"currency","USDC","network",network,"asset_id","asset_"+key,
                "network_id",networkId,"address_id","addr_"+key,"address",address,"memo",memo,"created_at",now()));
    }

    @Test void evmGenerationReusesRegisteredBscWithoutCreatingAnotherChain() {
        cache.configureEvm();cache.getEvm("BSC");
        clearInvocations(cache.client);
        var reused=management.generate("mcag_test","EVM","COMPUTE");
        assertEquals("BSC",reused.get("network"));
        assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address WHERE network_id<>'TRON'",Integer.class));
        verifyNoInteractions(cache.client);
    }

    @Test void evmNetworksShareOneDirectoryEntryAndDisableAllRegisteredChainsWithinTheWallet() {
        cache.configureEvm();
        cache.getEvm("ETH");cache.getEvm("BSC");cache.getEvm("POL");
        long project=seedHistory(1,"PROJECT","BSC_MAINNET","BSC","0x"+"a".repeat(40),"","token_1_project");
        var compute=management.page(1,20,"mcag_test","EVM","ACTIVE");
        assertEquals(1L,compute.get("total"));
        var row=(Map<?,?>)((List<?>)compute.get("records")).get(0);
        assertEquals("EVM",row.get("network"));
        assertEquals(1L,management.page(1,20,"mcag_test","ETH","ACTIVE").get("total"));
        assertEquals(1,management.networks("COMPUTE").stream().filter(item->"EVM".equals(item.get("network"))).count());
        clearInvocations(cache.client);
        management.generate("mcag_test","EVM","COMPUTE");
        verifyNoInteractions(cache.client);
        long id=number(row.get("id"));management.disable(id);management.disable(id);
        assertEquals(3,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address WHERE wallet_type='COMPUTE' AND disabled_at IS NOT NULL",Integer.class));
        assertEquals(3,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_address_generation WHERE wallet_type='COMPUTE' AND network_id<>'TRON' AND generation=1",Integer.class));
        assertNull(fixture.data.one("SELECT disabled_at FROM compute_mcag_address WHERE id=?",project).get("disabledAt"));
        assertEquals(1L,management.page(1,20,"mcag_test","EVM","DISABLED").get("total"));
    }

    @Test void evmAddressAliasUsesNormalizedOwnershipForDisabledLateReceipts() {
        var channel=new org.token.config.compute.ComputeMcagProperties.Channel();
        channel.setCurrency("ETH");channel.setNetwork("ERC20");channel.setNetworkId("ETHEREUM_MAINNET");channel.setAssetId("ETH_ETHEREUM");
        fixture.tx.executeWithoutResult(ignored->fixture.deposits.bind(1,"COMPUTE",channel,fields(
                "merchant_id","mch_token","environment","LIVE","customer_id","token_1_compute","network_id","ETHEREUM_MAINNET","purpose","CUSTOMER_DEPOSIT",
                "status","ACTIVE","monitoring_ready",true,"address_id","evm_old","address","0x"+"a".repeat(40))));
        management.disable(currentId());
        channel.setCurrency("USDC");channel.setAssetId("USDC_ETHEREUM");
        long receipt=fixture.tx.execute(ignored->fixture.deposits.rememberBinding(1,"COMPUTE",channel,"evm_alias","0x"+"A".repeat(40),"","token_1_compute"));
        assertNotNull(fixture.data.one("SELECT disabled_at FROM compute_mcag_address WHERE id=?",receipt).get("disabledAt"));
        fixture.tx.executeWithoutResult(ignored->{
            fixture.data.one("SELECT id FROM compute_member WHERE id=1 FOR UPDATE");
            fixture.deposits.prepareIdentity(1,"COMPUTE",channel);
        });
        assertNotNull(fixture.deposits.customerIdentity("token_1_compute_1"));
        channel.setCurrency("TEST");channel.setAssetId("TEST_ETHEREUM");
        long nextGeneration=fixture.tx.execute(ignored->fixture.deposits.rememberBinding(1,"COMPUTE",channel,"evm_new_generation_alias","0x"+"A".repeat(40),"","token_1_compute_1"));
        assertNotNull(fixture.data.one("SELECT disabled_at FROM compute_mcag_address WHERE id=?",nextGeneration).get("disabledAt"),"新客户代次、不同地址ID或币种不能绕过同一已停用地址");
        var addressRepository = new org.token.repository.compute.ComputeMcagAddressRepository(fixture.data);
        fixture.jdbc.update("INSERT INTO compute_mcag_address (member_id,wallet_type,currency,network,merchant_id,environment,network_id,asset_id,customer_id,address_id,address,disabled_at,created_at) VALUES (1,'COMPUTE','USDT','TRC20','mch_token','LIVE','TRON','USDT_TRON','token_1_compute','tron_case_sensitive',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "T" + "a".repeat(33));
        var exactAddress = new org.token.model.query.DepositAddressIdentity("mch_token", "LIVE", 1, "COMPUTE", "TRON", "unknown", "T" + "a".repeat(33));
        var differentCase = new org.token.model.query.DepositAddressIdentity("mch_token", "LIVE", 1, "COMPUTE", "TRON", "unknown", "T" + "A".repeat(33));
        assertEquals(1, addressRepository.disabledIds(exactAddress).size());
        assertTrue(addressRepository.disabledIds(differentCase).isEmpty(), "TRON 地址保持大小写精确匹配");
    }
    @Test void businessStatusAndNetworkReadinessAreIndependentInAddressManagement() {
        var states=cache.configureEvm();states.put("BSC",false);cache.getEvm("BSC");
        var page=management.page(1,20,"mcag_test","BSC","ACTIVE");
        assertEquals(1L,page.get("total"));
        var rows=(List<?>)page.get("records");var row=(Map<?,?>)rows.get(0);
        assertEquals("ACTIVE",row.get("status"));assertFalse(enabled(row.get("monitoringReady")));
        assertFalse(row.containsKey("initializationStatus"));
        states.put("BSC",true);cache.expire();cache.getEvm("BSC");
        var ready=(Map<?,?>)((List<?>)management.page(1,20,"mcag_test","BSC","ACTIVE").get("records")).get(0);
        assertTrue(enabled(ready.get("monitoringReady")));assertEquals(row.get("id"),ready.get("id"));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM compute_deposit",Integer.class));
    }
}
