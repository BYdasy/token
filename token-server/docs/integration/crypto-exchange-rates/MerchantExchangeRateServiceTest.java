package com.mcag.core;

import com.mcag.common.BusinessException;
import com.mcag.domain.Tenant;
import com.mcag.infrastructure.Db;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.mcag.common.Values.map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离模拟查询，不访问实际数据库、行情服务或资金接口。 */
class MerchantExchangeRateServiceTest {
    @Test void queryScopesVisibleAssetsAndProjectsOnlyCachedPublicPrices() {
        Db db=mock(Db.class);Instant now=Instant.now();
        when(db.list(anyString(),anyMap())).thenReturn(List.of(map("asset_id","BTC_NATIVE","symbol","BTC","network_id","BITCOIN_MAINNET",
                "snapshot_id","fx_test","usdt_price","60000","source","COINGECKO","observed_at",now.minusSeconds(30),"expires_at",now.plusSeconds(500),"private_key","禁止回显")));
        var result=new MerchantExchangeRateService(db).query(new Tenant("merchant_a","LIVE"),Map.of("asset_ids",List.of("BTC_NATIVE")));
        assertEquals("USDT",result.get("quote_currency"));assertFalse(result.toString().contains("禁止回显"));
        assertEquals("AVAILABLE",((Map<?,?>)((List<?>)result.get("items")).get(0)).get("status"));
        verify(db).list(argThat(sql->sql.contains("p.merchant_id=#{p.m}")&&sql.contains("p.environment=#{p.e}")&&sql.contains("p.visible")&&sql.contains("price_policy_version=f.configuration_version")),
                eq(Map.of("m","merchant_a","e","LIVE","asset0","BTC_NATIVE")));
        verifyNoMoreInteractions(db);
    }
    @Test void staleQuoteIsMarkedWithoutCreatingPriceOrCallingProvider() {
        Db db=mock(Db.class);Instant old=Instant.now().minusSeconds(1000);
        when(db.list(anyString(),anyMap())).thenReturn(List.of(map("asset_id","BTC_NATIVE","symbol","BTC","network_id","BITCOIN_MAINNET",
                "snapshot_id","fx_old","usdt_price","1","source","COINGECKO","observed_at",old,"expires_at",old.plusSeconds(600))));
        var result=new MerchantExchangeRateService(db).query(new Tenant("m","LIVE"),Map.of("asset_ids",List.of("BTC_NATIVE")));
        assertEquals("STALE",((Map<?,?>)((List<?>)result.get("items")).get(0)).get("status"));
        verify(db).list(anyString(),anyMap());verifyNoMoreInteractions(db);
    }
    @Test void invalidUnboundedOrDuplicateAssetsAreRejectedBeforeQuery() {
        Db db=mock(Db.class);var service=new MerchantExchangeRateService(db);var tenant=new Tenant("m","LIVE");
        for(var body:List.of(Map.of("asset_ids",List.of()),Map.of("asset_ids",List.of("BTC","BTC")),Map.of("asset_ids",List.of("invalid'")),Map.of("asset_ids",Collections.nCopies(101,"BTC"))))
            assertThrows(BusinessException.class,()->service.query(tenant,new HashMap<>(body)));
        verifyNoInteractions(db);
    }
}
