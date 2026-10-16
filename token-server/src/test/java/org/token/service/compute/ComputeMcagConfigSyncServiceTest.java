package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static org.token.repository.compute.ComputeData.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 同步边界验证使用隔离替身，不连接数据库或Crypto。 */
class ComputeMcagConfigSyncServiceTest {
    @Test void sameSymbolKeepsIndependentChainPrecisionAndDropsNonPublicFields() {
        var bsc=asset("USDC_BSC","BSC_MAINNET","BNB",18);
        var polygon=asset("USDC_POLYGON","POLYGON_MAINNET","POL",6);
        bsc.put("rpc_url","hidden");bsc.put("private_key","hidden");
        var result=ComputeMcagConfigSyncService.validateAssets(fields("items",List.of(bsc,polygon)));
        assertEquals("18",result.get(0).get("decimals"));assertEquals("6",result.get(1).get("decimals"));
        assertFalse(result.get(0).containsKey("rpc_url"));assertFalse(result.get(0).containsKey("private_key"));
        assertEquals(18,bsc.get("decimals"));
    }

    @Test void partialDuplicateAndInconsistentNetworkResponsesAreRejectedBeforeWrites() {
        var bsc=asset("USDC_BSC","BSC_MAINNET","BNB",18);
        assertThrows(BusinessException.class,()->ComputeMcagConfigSyncService.validateAssets(fields("items",List.of(bsc),"has_more",true)));
        assertThrows(BusinessException.class,()->ComputeMcagConfigSyncService.validateAssets(fields("items",List.of(bsc,bsc))));
        var incomplete=asset("USDC_BSC","BSC_MAINNET","BNB",18);incomplete.remove("family");
        assertThrows(BusinessException.class,()->ComputeMcagConfigSyncService.validateAssets(fields("items",List.of(incomplete))));
        var wrong=asset("USDT_BSC","BSC_MAINNET","ETH",18);wrong.put("symbol","USDT");
        assertThrows(BusinessException.class,()->ComputeMcagConfigSyncService.validateAssets(fields("items",List.of(bsc,wrong))));
    }

    @Test void existingCodesAndNativeNetworkMatchArePreservedWithoutYamlRuntimeFallback() {
        var remote=List.of(asset("USDC_BSC","BSC_MAINNET","BNB",18),asset("USDC_POLYGON","POLYGON_MAINNET","POL",6));
        var existing=List.of(fields("currency","USDC","network","MY_BSC","assetId","USDC_BSC","networkId","BSC_MAINNET"));
        var networks=List.of(network("MY_BSC","BNB"),network("MY_POLYGON","POL"));
        var result=ComputeMcagConfigSyncService.plan(remote,existing,networks,List.of());
        assertEquals("MY_BSC",result.get(0).network());assertEquals("MY_POLYGON",result.get(1).network());
    }

    @Test void ambiguousNetworksAndChangedRemoteIdentityCannotBeSilentlyRebound() {
        var remote=List.of(asset("USDC_BSC","BSC_MAINNET","BNB",18),asset("USDC_POLYGON","POLYGON_MAINNET","POL",6));
        assertThrows(BusinessException.class,()->ComputeMcagConfigSyncService.plan(remote,List.of(),List.of(network("UNSPECIFIED","")),List.of()));
        assertThrows(BusinessException.class,()->ComputeMcagConfigSyncService.plan(List.of(remote.get(0)),List.of(),List.of(network("BSC_A","BNB"),network("BSC_B","BNB")),List.of()));
        var old=List.of(fields("currency","USDC","network","BSC","assetId","USDC_BSC","networkId","OLD_MAINNET"));
        assertThrows(BusinessException.class,()->ComputeMcagConfigSyncService.plan(List.of(remote.get(0)),old,List.of(network("BSC","BNB")),List.of()));
    }

    @Test void bootstrapUsesExactConfiguredMappingButRejectsSymbolOrNetworkMismatch() {
        var seed=new ComputeMcagProperties.Channel();seed.setCurrency("USDC");seed.setNetwork("SAVED_BSC");seed.setAssetId("USDC_BSC");seed.setNetworkId("BSC_MAINNET");
        var remote=List.of(asset("USDC_BSC","BSC_MAINNET","BNB",18));
        assertEquals("SAVED_BSC",ComputeMcagConfigSyncService.plan(remote,List.of(),List.of(),List.of(seed)).get(0).network());
        seed.setNetworkId("ETHEREUM_MAINNET");
        assertThrows(BusinessException.class,()->ComputeMcagConfigSyncService.plan(remote,List.of(),List.of(),List.of(seed)));
    }

    @Test void baseNativeAndUsdcRemainOnExactBaseNetworkBesideExistingEthereumMapping() {
        var base=asset("USDC_BASE","BASE_MAINNET","ETH",6);base.put("chain_id",8453);base.put("native_asset_id","ETH_BASE");
        var nativeBase=new java.util.LinkedHashMap<>(base);nativeBase.put("asset_id","ETH_BASE");nativeBase.put("symbol","ETH");nativeBase.put("asset_type","NATIVE");nativeBase.put("decimals",18);
        var ethereum=asset("USDC_ETHEREUM","ETHEREUM_MAINNET","ETH",6);ethereum.put("chain_id",1);ethereum.put("native_asset_id","ETH_ETHEREUM");
        var existing=List.of(fields("currency","USDC","network","ERC20","assetId","USDC_ETHEREUM","networkId","ETHEREUM_MAINNET"));
        var networks=List.of(network("ERC20","ETH"));
        var remote=ComputeMcagConfigSyncService.validateAssets(fields("items",List.of(nativeBase,base,ethereum)));
        var plans=ComputeMcagConfigSyncService.plan(remote,existing,networks,List.of());
        assertEquals(List.of("BASE_MAINNET","BASE_MAINNET","ERC20"),plans.stream().map(ComputeMcagConfigSyncService.Plan::network).toList());
        assertEquals(List.of("ETH","USDC","USDC"),plans.stream().map(ComputeMcagConfigSyncService.Plan::currency).toList());
        assertEquals("ETH_BASE",plans.get(0).remote().get("asset_id"));assertEquals("USDC_BASE",plans.get(1).remote().get("asset_id"));
    }

    @Test void baseAloneCannotGuessEthereumNetworkFromTheSameNativeCurrency() {
        var base=asset("USDC_BASE","BASE_MAINNET","ETH",6);
        assertEquals("BASE_MAINNET",ComputeMcagConfigSyncService.plan(List.of(base),List.of(),List.of(network("ERC20","ETH")),List.of()).get(0).network());
        var saved=List.of(fields("currency","USDC","network","SAVED_BASE","assetId","USDC_BASE","networkId","BASE_MAINNET"));
        assertEquals("SAVED_BASE",ComputeMcagConfigSyncService.plan(List.of(base),saved,List.of(network("SAVED_BASE","ETH")),List.of()).get(0).network());
    }

    @Test void polygonUsdt0UpgradeKeepsExistingCurrencyIdentityAndDoesNotBlockBase() {
        var polygon=asset("USDT_POLYGON","POLYGON_MAINNET","POL",6);polygon.put("symbol","USDT0");
        var base=asset("USDC_BASE","BASE_MAINNET","ETH",6);
        var mappings=List.of(fields("currency","USDT","network","POL","assetId","USDT_POLYGON","networkId","POLYGON_MAINNET"));
        var plans=ComputeMcagConfigSyncService.plan(List.of(polygon,base),mappings,List.of(network("POL","POL")),List.of());
        assertEquals("USDT",plans.get(0).currency());assertEquals("POL",plans.get(0).network());
        assertEquals("USDT0",plans.get(0).remote().get("symbol"));assertEquals("USDT_POLYGON",plans.get(0).remote().get("asset_id"));
        assertEquals("BASE_MAINNET",plans.get(1).network());assertEquals("USDC",plans.get(1).currency());
        var existingUsdt0=List.of(fields("currency","USDT0","network","POL","assetId","USDT_POLYGON","networkId","POLYGON_MAINNET"));
        assertEquals("USDT0",ComputeMcagConfigSyncService.plan(List.of(polygon),existingUsdt0,List.of(network("POL","POL")),List.of()).get(0).currency());
    }

    @Test void polygonCurrencyAliasIsLimitedToTheExactAssetAndNetworkIncludingBootstrap() {
        var polygon=asset("USDT_POLYGON","POLYGON_MAINNET","POL",6);polygon.put("symbol","USDT0");
        var seed=new ComputeMcagProperties.Channel();seed.setCurrency("USDT");seed.setNetwork("POL");seed.setAssetId("USDT_POLYGON");seed.setNetworkId("POLYGON_MAINNET");
        assertEquals("USDT",ComputeMcagConfigSyncService.plan(List.of(polygon),List.of(),List.of(network("POL","POL")),List.of(seed)).get(0).currency());
        for(String id:List.of("USDC_POLYGON","USDT_OTHER")) {
            var wrong=new java.util.LinkedHashMap<>(polygon);wrong.put("asset_id",id);
            var old=List.of(fields("currency","USDT","network","POL","assetId",id,"networkId","POLYGON_MAINNET"));
            assertThrows(BusinessException.class,()->ComputeMcagConfigSyncService.plan(List.of(wrong),old,List.of(network("POL","POL")),List.of()));
        }
        var wrongNetwork=new java.util.LinkedHashMap<>(polygon);wrongNetwork.put("network_id","BSC_MAINNET");
        var old=List.of(fields("currency","USDT","network","BSC","assetId","USDT_POLYGON","networkId","BSC_MAINNET"));
        assertThrows(BusinessException.class,()->ComputeMcagConfigSyncService.plan(List.of(wrongNetwork),old,List.of(network("BSC","POL")),List.of()));
    }

    @Test void multipleEthNativeNetworksDoNotDependOnRemoteOrderingOrReuseBoundNetworks() {
        var ethereum=asset("USDC_ETHEREUM","ETHEREUM_MAINNET","ETH",6);
        var arbitrum=asset("USDC_ARBITRUM","ARBITRUM_MAINNET","ETH",6);
        for(var remote:List.of(List.of(ethereum,arbitrum),List.of(arbitrum,ethereum))) {
            var plans=ComputeMcagConfigSyncService.plan(remote,List.of(),List.of(network("LEGACY_ETH","ETH")),List.of());
            assertTrue(plans.stream().allMatch(plan->plan.network().equals(plan.remote().get("network_id"))));
        }
        var existing=List.of(fields("currency","USDC","network","ERC20","assetId","USDC_ETHEREUM","networkId","ETHEREUM_MAINNET"));
        assertEquals("ARBITRUM_MAINNET",ComputeMcagConfigSyncService.plan(List.of(arbitrum),existing,List.of(network("ERC20","ETH")),List.of()).get(0).network());
    }

    @Test void outdatedRemoteResponseCannotApplyMappingsAndFetchFollowsAttemptCommit() {
        var data=mock(FixtureComputeData.class);
        var repository=mock(org.token.repository.compute.ComputeMcagConfigSyncRepository.class);
        var config=new ComputeMcagProperties();config.setEnabled(true);config.setMerchantId("merchant-1");
        var scope=mock(ComputeAgentScope.class);var client=mock(ComputeMcagClient.class);
        @SuppressWarnings("unchecked") ObjectProvider<ComputeMcagClient> clients=mock(ObjectProvider.class);when(clients.getIfAvailable()).thenReturn(client);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        when(repository.synchronizeMerchantSnapshotRow(any(Object[].class))).thenReturn(fields("lastAttemptAt","2026-09-20T00:00:00Z"));
        when(repository.synchronizeMerchantSnapshotRow2(any(Object[].class))).thenReturn(fields("lastAttemptAt","2026-09-20T00:00:01Z"));
        when(client.request("assets/query",Map.of())).thenReturn(fields("items",List.of(asset("USDC_BSC","BSC_MAINNET","BNB",18))));
        var service=new ComputeMcagConfigSyncService(data,config,clients,scope,manager,repository);
        assertEquals("server.compute.v15.currencySyncSuperseded",assertThrows(BusinessException.class,service::synchronize).getMessage());
        var order=inOrder(manager,client);order.verify(manager).commit(any());order.verify(client).request("assets/query",Map.of());
        verify(data,never()).insert(anyString(),anyMap());
        verify(repository,never()).applyMcagChannelWrite(any(Object[].class));
        verify(repository,never()).applyMcagChannelWrite2(any(Object[].class));
    }

    @Test void unchangedSyncPreservesDisabledLocalChannelFeesAndMappingVersion() {
        var jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:sync_"+java.util.UUID.randomUUID(),"sa",""));
        var data=spy(new FixtureComputeData(jdbc,new ObjectMapper()));
        var repository=mock(org.token.repository.compute.ComputeMcagConfigSyncRepository.class);
        var config=new ComputeMcagProperties();config.setEnabled(true);config.setMerchantId("merchant-1");
        var row=asset("USDC_BSC","BSC_MAINNET","BNB",18);
        var metadata=ComputeMcagConfigSyncService.validateAssets(fields("items",List.of(row))).get(0);
        var mapping=fields("id",9,"currency","USDC","network","BSC","assetId","USDC_BSC","networkId","BSC_MAINNET",
                "active",true,"metadataJson",data.json(metadata));
        var local=fields("id",7,"currency","USDC","network","BSC","amountScale",6,
                "enabled",false,"depositEnabled",false,"withdrawEnabled",false,"withdrawFeeFixed","3");
        when(repository.applyMcagChannelRows(any(Object[].class))).thenReturn(List.of(mapping));
        when(repository.applyCurrencyAssetRows()).thenReturn(List.of(fields("assetCode","USDC","assetType","CRYPTO","amountScale",8)));
        when(repository.applyCurrencyNetworkRows()).thenReturn(List.of(network("BSC","BNB")));
        when(repository.applyCurrencyRows()).thenReturn(List.of(local));
        var snapshot=fields("lastAttemptAt","2026-09-20T00:00:00Z","snapshotJson",data.json(fields("mappingInitialized",true)));
        when(repository.synchronizeMerchantSnapshotRow(any(Object[].class))).thenReturn(snapshot);
        when(repository.synchronizeMerchantSnapshotRow2(any(Object[].class))).thenReturn(snapshot);
        var client=mock(ComputeMcagClient.class);when(client.request("assets/query",Map.of())).thenReturn(fields("items",List.of(row)));
        @SuppressWarnings("unchecked") ObjectProvider<ComputeMcagClient> clients=mock(ObjectProvider.class);when(clients.getIfAvailable()).thenReturn(client);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        new ComputeMcagConfigSyncService(data,config,clients,mock(ComputeAgentScope.class),manager,repository).synchronize();
        verify(data,never()).insert(anyString(),anyMap());
        verify(repository).applyCurrencyWrite(
                eq("ERC20"),eq("0x123"),eq(6),any(LocalDateTime.class),eq(7));
        verify(repository).applyMcagChannelWrite(
                eq(18),eq(true),eq(false),eq(0),eq(1L),anyString(),any(LocalDateTime.class),any(LocalDateTime.class),eq(9));
        assertEquals(false,local.get("enabled"));assertEquals("3",local.get("withdrawFeeFixed"));
    }

    private static Map<String,Object> network(String code,String nativeSymbol) {
        return fields("networkCode",code,"networkType","CRYPTO","chainType","EVM","nativeAssetCode",nativeSymbol);
    }
    private static Map<String,Object> asset(String id,String network,String nativeSymbol,int precision) {
        return fields("asset_id",id,"symbol","USDC","network_id",network,"asset_type","TOKEN","decimals",precision,
                "family","EVM","network_name",network,"native_asset_id",nativeSymbol+"_NATIVE","native_symbol",nativeSymbol,"native_decimals",18,
                "chain_id",network.equals("BSC_MAINNET")?56:137,"finality_type","CONFIRMATIONS","required_confirmations",12,
                "configuration_version",1,"network_configuration_version",1,"deposit_enabled",true,"payout_enabled",false,
                "minimum_deposit","1","minimum_payout","1","ignore_amount","0","sweep_threshold","1","contract_address","0x123","token_standard","ERC20");
    }
}
