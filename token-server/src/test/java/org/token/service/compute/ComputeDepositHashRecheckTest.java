package org.token.service.compute;

import org.token.config.compute.ComputeMcagProperties;
import org.token.controller.admin.ComputeGatewayAdminController;
import org.token.exception.BusinessException;
import org.token.model.request.compute.DepositHashRecheckRequest;
import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeDisplayNameRepository;
import org.token.repository.compute.ComputeMerchantRepository;
import org.token.service.TotpService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 按真实 Crypto 商户协议核对补查线索与权限，全程隔离网关和资金数据库。 */
class ComputeDepositHashRecheckTest {
    private final ComputeMcagClient client=mock(ComputeMcagClient.class);
    private final ComputeMcagProperties config=new ComputeMcagProperties();
    private final ComputeAgentScope scope=mock(ComputeAgentScope.class);
    private final TotpService totp=mock(TotpService.class);
    private final ComputeData data=mock(ComputeData.class);
    private final ComputeMerchantRepository repository=mock(ComputeMerchantRepository.class);
    private final ComputeDisplayNameRepository names=mock(ComputeDisplayNameRepository.class);
    private ComputeMerchantService service;
    private final String hash="a".repeat(64);

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        ObjectProvider<ComputeMcagClient> clients=mock(ObjectProvider.class);
        when(clients.getIfAvailable()).thenReturn(client);
        config.setEnabled(true);config.setMerchantId("token_merchant");config.setEnvironment("LIVE");
        when(scope.currentAdminUid()).thenReturn(7);
        when(totp.verify(7,"123456",true)).thenReturn(true);
        service=new ComputeMerchantService(clients,config,scope,totp,data,null,repository,names);
        login("super");
        when(client.request(eq("assets/query"),anyMap())).thenReturn(Map.of("items",List.of(asset("USDT_TRON","TRON_MAINNET","TRON",true))));
        when(client.request(eq("deposit-rechecks/create"),anyMap())).thenAnswer(call->{
            Map<String,Object> body=call.getArgument(1);
            return new LinkedHashMap<>(Map.of("recheck_id","recheck1","merchant_recheck_id",body.get("merchant_recheck_id"),
                    "merchant_id","token_merchant","environment","LIVE","asset_id",body.get("asset_id"),
                    "network_id",body.get("network_id"),"tx_hash",body.get("tx_hash"),"status","PENDING"));
        });
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    private void login(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority(authority))));
    }
    private Map<String,Object> asset(String asset,String network,String family,boolean enabled) {
        return Map.of("asset_id",asset,"network_id",network,"symbol","USDT","network_name",network,"family",family,"deposit_enabled",enabled,"internal_secret","hidden");
    }
    private DepositHashRecheckRequest input() {
        return new DepositHashRecheckRequest("check_same","USDT_TRON","TRON_MAINNET",hash,"T-receiving-address","log:1","123456");
    }

    @Test void optionsOnlyExposeCurrentMerchantAuthorizedDepositAssets() {
        when(client.request("assets/query",Map.of())).thenReturn(Map.of("items",List.of(
                asset("USDT_TRON","TRON_MAINNET","TRON",true),asset("TRX_TRON","TRON_MAINNET","TRON",false))));
        var options=service.depositRecheckOptions();
        assertEquals("token_merchant",options.get("merchantId"));assertEquals("LIVE",options.get("environment"));
        assertEquals(List.of(Map.of("assetId","USDT_TRON","networkId","TRON_MAINNET","networkName","TRON_MAINNET","family","TRON","symbol","USDT","displayName","USDT")),options.get("assets"));
        verifyNoInteractions(totp,data,repository,names);
    }

    @Test void rawAddressRecheckUsesFixedTenantAndOriginalRequestWithoutCreditingMoney() {
        var result=service.depositRecheck(input());service.depositRecheck(input());
        assertEquals(Map.of("recheckId","recheck1","requestId","check_same","status","PENDING"),result);
        verify(client,times(2)).request("deposit-rechecks/create",Map.of("merchant_recheck_id","check_same","asset_id","USDT_TRON",
                "network_id","TRON_MAINNET","tx_hash",hash,"address","T-receiving-address","transfer_locator","log:1"));
        verify(totp,times(2)).verify(7,"123456",true);
        verifyNoInteractions(data,repository,names);
    }

    @Test void evmHashIsNormalizedAndBlankLocatorIsOmitted() {
        when(client.request("assets/query",Map.of())).thenReturn(Map.of("items",List.of(asset("USDT_ETH","ETHEREUM_MAINNET","EVM",true))));
        service.depositRecheck(new DepositHashRecheckRequest("evm1","USDT_ETH","ETHEREUM_MAINNET","0X"+"A".repeat(64),"  0xReceivedAddress  ","  ","123456"));
        verify(client).request("deposit-rechecks/create",Map.of("merchant_recheck_id","evm1","asset_id","USDT_ETH","network_id","ETHEREUM_MAINNET",
                "tx_hash","0x"+hash,"address","0xReceivedAddress"));
    }

    @Test void mismatchedAssetAndNetworkOrInvalidHashCannotCreateTask() {
        var badNetwork=new DepositHashRecheckRequest("check_same","USDT_TRON","ETHEREUM_MAINNET",hash,"T-address",null,"123456");
        assertEquals("server.compute.v47Recheck.assetUnavailable",assertThrows(BusinessException.class,()->service.depositRecheck(badNetwork)).getMessage());
        var badHash=new DepositHashRecheckRequest("check_same","USDT_TRON","TRON_MAINNET","0x"+hash,"T-address",null,"123456");
        assertEquals("server.compute.v47Recheck.invalidHash",assertThrows(BusinessException.class,()->service.depositRecheck(badHash)).getMessage());
        verify(client,never()).request(eq("deposit-rechecks/create"),anyMap());
    }

    @Test void superAndFreshTotpAreRequiredBeforeAnyExternalRequest() {
        login("30101");
        assertThrows(BusinessException.class,service::depositRecheckOptions);
        assertThrows(BusinessException.class,()->service.depositRecheck(input()));
        verifyNoInteractions(client,totp);
        login("super");when(totp.verify(7,"123456",true)).thenReturn(false);
        assertThrows(BusinessException.class,()->service.depositRecheck(input()));
        verifyNoInteractions(client);
    }

    @Test void scopedAdminCannotInspectOrSubmitForWholeMerchant() {
        doThrow(new BusinessException(403,"server.compute.management.outOfScope")).when(scope).requireGlobalScope();
        assertThrows(BusinessException.class,service::depositRecheckOptions);
        assertThrows(BusinessException.class,()->service.depositRecheck(input()));
        verifyNoInteractions(client,totp);
    }

    @Test void foreignOrMismatchedResponseIsNeverPresentedAsAccepted() {
        for(var entry:Map.of("merchant_id","other","environment","OTHER","merchant_recheck_id","other","asset_id","other","network_id","other","tx_hash","other").entrySet()) {
            var response=new HashMap<String,Object>(Map.of("recheck_id","r1","merchant_id","token_merchant","environment","LIVE","merchant_recheck_id","check_same",
                    "asset_id","USDT_TRON","network_id","TRON_MAINNET","tx_hash",hash,"status","PENDING"));
            response.put(entry.getKey(),entry.getValue());
            doReturn(response).when(client).request(eq("deposit-rechecks/create"),anyMap());
            assertEquals("server.compute.payment.invalidResponse",assertThrows(BusinessException.class,()->service.depositRecheck(input())).getMessage());
        }
        verifyNoInteractions(data,repository,names);
    }

    @Test void resolvedAndNotFoundReplaysRemainRecheckResultsRatherThanCreditedMoney() {
        for (String status:List.of("NOT_FOUND","RESOLVED","REJECTED","RETRY")) {
            var response=new HashMap<String,Object>(Map.of("recheck_id","r1","merchant_id","token_merchant","environment","LIVE","merchant_recheck_id","check_same",
                    "asset_id","USDT_TRON","network_id","TRON_MAINNET","tx_hash",hash,"status",status));
            response.put("deposit_ids",List.of("remote_order"));response.put("request_fingerprint","private");response.put("posting_ids",List.of("remote_ledger"));
            doReturn(response).when(client).request(eq("deposit-rechecks/create"),anyMap());
            assertEquals(Map.of("recheckId","r1","requestId","check_same","status",status),service.depositRecheck(input()));
        }
        verifyNoInteractions(data,repository,names);
    }

    @Test void onlyVerifiedGatewayErrorsReceiveSpecificAddressMessage() {
        doThrow(new ComputeMcagRejectedException("ADDRESS_NOT_FOUND")).when(client).request(eq("deposit-rechecks/create"),anyMap());
        var rejection=assertThrows(BusinessException.class,()->service.depositRecheck(input()));
        assertEquals("server.compute.v47Recheck.addressMismatch",rejection.getMessage());
        assertEquals(Map.of("recheckSubmission","NOT_SUBMITTED"),rejection.getData());
        doThrow(new BusinessException(504,"server.compute.payment.timeout")).when(client).request(eq("deposit-rechecks/create"),anyMap());
        var timeout=assertThrows(BusinessException.class,()->service.depositRecheck(input()));
        assertEquals("server.compute.payment.timeout",timeout.getMessage());assertEquals("",timeout.getData());
        doThrow(new ComputeMcagRejectedException("IDEMPOTENCY_CONFLICT")).when(client).request(eq("deposit-rechecks/create"),anyMap());
        assertEquals("",assertThrows(BusinessException.class,()->service.depositRecheck(input())).getData());
        verifyNoInteractions(data,repository,names);
    }

    @Test void invalidCluesAreRejectedWithoutExternalCalls() {
        for(var request:List.of(new DepositHashRecheckRequest("bad/id","USDT_TRON","TRON_MAINNET",hash,"T-address",null,"123456"),
                new DepositHashRecheckRequest("id","USDT_TRON","TRON_MAINNET",hash," ",null,"123456"),
                new DepositHashRecheckRequest("id","USDT_TRON","TRON_MAINNET",hash,"address","x".repeat(81),"123456"),
                new DepositHashRecheckRequest("id","USDT_TRON","TRON_MAINNET",hash,"address\n",null,"123456")))
            assertThrows(BusinessException.class,()->service.depositRecheck(request));
        verifyNoInteractions(client,totp);
    }

    @Test void financeEndpointsRequireSuperDirectlyAndAuditSubmission() throws Exception {
        var create=ComputeGatewayAdminController.class.getMethod("depositRecheck",DepositHashRecheckRequest.class);
        assertEquals("hasAuthority('super')",create.getAnnotation(PreAuthorize.class).value());
        assertNotNull(create.getAnnotation(org.token.custom.LogAction.class));
        assertEquals("hasAuthority('super')",ComputeGatewayAdminController.class.getMethod("depositRecheckOptions").getAnnotation(PreAuthorize.class).value());
    }
}
