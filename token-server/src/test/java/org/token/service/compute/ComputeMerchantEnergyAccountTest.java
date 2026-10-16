package org.token.service.compute;

import org.token.config.compute.ComputeMcagProperties;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeDisplayNameRepository;
import org.token.repository.compute.ComputeMerchantRepository;
import org.token.service.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 本地只读边界；全部网关与数据库对象为模拟，不访问实际资金或服务。 */
class ComputeMerchantEnergyAccountTest {
    private final ComputeMcagClient client=mock(ComputeMcagClient.class);
    private final ComputeAgentScope scope=mock(ComputeAgentScope.class);
    private final ComputeMerchantRepository repository=mock(ComputeMerchantRepository.class);
    private ComputeMerchantService service;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        ObjectProvider<ComputeMcagClient> clients=mock(ObjectProvider.class);
        when(clients.getIfAvailable()).thenReturn(client);
        var config=new ComputeMcagProperties();config.setEnabled(true);config.setMerchantId("current");
        service=new ComputeMerchantService(clients,config,scope,mock(TotpService.class),mock(ComputeData.class),null,repository,mock(ComputeDisplayNameRepository.class));
    }
    private Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();
        result.put("mode","INDEPENDENT");result.put("status","READY");result.put("configured",true);result.put("enabled",true);
        result.put("balance_trx","123.123456789012345678");result.put("balance_usdt","0");
        result.put("name","当前供应商");result.put("recharge_address","public-address");
        return result;
    }
    @Test void readsOnlyCurrentSupplierAndStripsUnexpectedSensitiveFields() {
        var response=snapshot();response.put("account_id","hidden");response.put("api_key","secret");response.put("last_sync_error_code","private body");
        when(client.request("energy-account/query",Map.of())).thenReturn(response);
        var result=service.energyAccount();
        assertEquals("123.123456789012345678",result.get("balance_trx"));assertEquals("0",result.get("balance_usdt"));
        assertFalse(result.containsKey("account_id"));assertFalse(result.containsKey("api_key"));assertFalse(result.containsKey("last_sync_error_code"));
        verify(client).request("energy-account/query",Map.of());verifyNoMoreInteractions(client);verifyNoInteractions(repository);
    }
    @Test void unavailableBalancesStayNullAndInvalidProtocolCannotLookSuccessful() {
        var response=snapshot();response.put("status","UNINITIALIZED");response.put("balance_trx",null);response.put("balance_usdt",null);
        when(client.request("energy-account/query",Map.of())).thenReturn(response);
        assertNull(service.energyAccount().get("balance_trx"));
        for(var malformed:List.of(Map.<String,Object>of(),Map.<String,Object>of("mode","GLOBAL"))) {
            when(client.request("energy-account/query",Map.of())).thenReturn(malformed);
            assertEquals("server.compute.payment.invalidResponse",assertThrows(BusinessException.class,service::energyAccount).getMessage());
        }
        response.put("balance_trx",0.25);when(client.request("energy-account/query",Map.of())).thenReturn(response);
        assertThrows(BusinessException.class,service::energyAccount);
    }
    @Test void filterOptionsExposeOnlyMerchantVisibleIdentifiersAndLabels() {
        when(client.request("assets/query",Map.of())).thenReturn(Map.of("items",List.of(Map.of("asset_id","USDC_BASE","network_id","BASE_MAINNET","symbol","USDC","network_name","Base","internal_fee","secret"))));
        assertEquals(Map.of("items",List.of(Map.of("asset_id","USDC_BASE","network_id","BASE_MAINNET","symbol","USDC","network_name","Base"))),service.filterOptions());
        verify(client).request("assets/query",Map.of());verifyNoMoreInteractions(client);verifyNoInteractions(repository);
    }
    @Test void bothNewReadEndpointsRejectLimitedScopeBeforeExternalRequest() {
        doThrow(new BusinessException(403,"server.compute.management.outOfScope")).when(scope).requireGlobalScope();
        assertThrows(BusinessException.class,service::energyAccount);assertThrows(BusinessException.class,service::filterOptions);
        verifyNoInteractions(client,repository);
    }
    @Test void reasonFilterReachesAllThreeOriginalMerchantQueriesWithoutChangingTheirPagination() {
        when(client.request(anyString(),anyMap())).thenReturn(Map.of("items",List.of(),"view","CURRENT","total",0));
        service.query("batches",Map.of("reason_code"," SWEEP_PENDING ","before_id","batch-last"));
        service.query("sweeps",Map.of("reason_code","ENERGY_DELIVERY_PENDING","before_id","operation-last"));
        service.query("resources",Map.of("reason_code","RESOURCE_DELIVERY_PENDING","page","7"));
        verify(client).request("collection-batches/query",Map.of("reason_code","SWEEP_PENDING","before_id","batch-last","limit",20,"view","summary"));
        verify(client).request("sweeps/query",Map.of("reason_code","ENERGY_DELIVERY_PENDING","before_id","operation-last","limit",20));
        verify(client).request("resource-orders/query",Map.of("reason_code","RESOURCE_DELIVERY_PENDING","page",7,"limit",20,"view","CURRENT"));
    }
    @Test void malformedReasonIsRejectedBeforeCallingCrypto() {
        for(String resource:List.of("batches","sweeps","resources"))for(String reason:List.of("x".repeat(257),"BAD\nCODE"))
            assertThrows(BusinessException.class,()->service.query(resource,Map.of("reason_code",reason)));
        verifyNoInteractions(client,repository);
    }
}
