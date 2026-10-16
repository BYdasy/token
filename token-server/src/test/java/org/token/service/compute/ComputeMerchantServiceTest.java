package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.service.TotpService;
import org.token.config.compute.ComputeMcagProperties;
import org.token.controller.admin.ComputeMerchantController;
import org.token.controller.admin.ComputeGatewayAdminController;
import org.token.controller.admin.ComputeMcagController;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.compute.ComputePaymentGateway;
import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeMerchantRepository;
import org.token.repository.compute.ComputeDisplayNameRepository;
import org.token.service.compute.ComputeAgentScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 商户边界测试只使用模拟网关，不进行实际网络调用、链上操作或业务数据库写入。 */
class ComputeMerchantServiceTest {
    private final ComputeMcagClient client = mock(ComputeMcagClient.class);
    private final ComputeMcagProperties config = new ComputeMcagProperties();
    private final ComputeAgentScope scope = mock(ComputeAgentScope.class);
    private final TotpService totp = mock(TotpService.class);
    private final ComputeData data = mock(ComputeData.class);
    private final ComputeMerchantRepository repository = mock(ComputeMerchantRepository.class);
    private final ComputeDisplayNameRepository displayNames = mock(ComputeDisplayNameRepository.class);
    private ComputeMerchantService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        ObjectProvider<ComputeMcagClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        config.setEnabled(true); config.setMerchantId("merchant_test");
        when(scope.isGlobalScope()).thenReturn(true); when(scope.currentAdminUid()).thenReturn(7);
        service = new ComputeMerchantService(provider, config, scope, totp, data, null, repository, displayNames);
    }

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        // 独立调用控制器不会经过日志切面 finally，由测试清理同一线程的日志上下文。
        Object holder = org.springframework.test.util.ReflectionTestUtils.getField(org.token.custom.LoggingAspect.class, "LOG_RECORD_HOLDER");
        if (holder instanceof ThreadLocal<?> local) local.remove();
    }
    private void login(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority(authority))));
    }

    @Test void queryUsesFixedOperationAndOnlyContractFields() {
        when(client.request(anyString(), anyMap())).thenReturn(Map.of("items", List.of()));
        service.query("wallets", Map.of("network_id", "TRON_MAINNET", "page", "3", "merchant_id", "other", "source_wallet", "attacker"));
        verify(client).request("merchant-wallets/query", Map.of("network_id", "TRON_MAINNET","view","summary"));
        assertThrows(BusinessException.class, () -> service.query("merchant-wallets/create", Map.of()));
    }

    @Test void nativePaginationKeepsCursorAndNumberedPagesSeparate() {
        when(client.request(anyString(), anyMap())).thenReturn(Map.of("items", List.of()));
        service.query("fee-ledger", Map.of("page", "2", "limit", "40", "before_id", "ignored"));
        service.query("batches", Map.of("page", "5", "limit", "20", "before_id", "batch_last", "status", "CREDITED"));
        verify(client).request("service-fee/ledger/query", Map.of("page", 2, "limit", 40));
        // 当前 Crypto 明确支持状态筛选；页码不能混入游标接口，状态条件则应完整保留。
        verify(client).request("collection-batches/query", Map.of("limit", 20, "before_id", "batch_last", "status", "CREDITED", "view", "summary"));
    }

    @Test void batchDetailsProxyExactlyOnePageAndRetainReceiptPaginationMetadata() {
        var remote = Map.<String, Object>of("batch_id", "batch1", "items", List.of(Map.of("receipt_id", "receipt2")),
                "receipt_count", 40, "receipt_has_more", true, "receipt_next_cursor", "receipt2", "resource_version", 9L);
        when(client.request(anyString(), anyMap())).thenReturn(Map.of("items", List.of(remote), "has_more", false));
        var result = service.query("batches", Map.of("batch_id", "batch1", "receipt_limit", "20",
                "receipt_after_id", "receipt1", "expected_resource_version", "9", "merchant_id", "other"));
        verify(client).request("collection-batches/query", Map.of("batch_id", "batch1", "view", "receipts", "receipt_limit", 20,
                "receipt_after_id", "receipt1", "expected_resource_version", 9L));
        assertEquals(List.of(remote), result.get("items"));
        verifyNoMoreInteractions(client);
        verifyNoInteractions(repository, displayNames);
    }

    @Test void malformedBatchPaginationIsRejectedBeforeRequest() {
        for (var input : List.of(Map.of("batch_id", "batch1", "receipt_after_id", "receipt1"),
                Map.of("batch_id", "batch1", "receipt_limit", "51"), Map.of("batch_id", "batch1", "receipt_limit", "0"),
                Map.of("batch_id", "batch1", "expected_resource_version", "1.5"),
                Map.of("batch_id", "batch1", "expected_resource_version", "0"),
                Map.of("receipt_after_id", "receipt1"), Map.of("view", "internal")))
            assertThrows(BusinessException.class, () -> service.query("batches", input));
        verifyNoInteractions(client);
    }

    @Test void resourceOrdersUseCurrentMerchantContractAndOnlyAllowedFilters() {
        var remote = Map.<String, Object>of("view", "CURRENT", "items", List.of(Map.of("request_id", "current1")), "total", 1);
        when(client.request(anyString(), anyMap())).thenReturn(remote);
        var result = service.query("resources", Map.of("page", "2", "limit", "30", "status", "WAITING",
                "address", "TRON_address", "resource_type", "ENERGY", "start_time", "2026-10-01T00:00:00Z",
                "merchant_id", "other", "environment", "other", "view", "LEGACY", "before_id", "ignored"));
        verify(client).request("resource-orders/query", Map.of("page", 2, "limit", 30, "view", "CURRENT",
                "status", "WAITING", "address", "TRON_address", "resource_type", "ENERGY", "start_time", "2026-10-01T00:00:00Z"));
        assertSame(remote, result);
        verify(scope).requireGlobalScope();
        verifyNoInteractions(repository, displayNames, totp);
    }

    @Test void resourceDetailsUseCurrentRequestIdAndKeepStepFacts() {
        var request = Map.of("request_id", "current1", "status", "WAITING");
        var steps = List.of(Map.of("step_id", "step1", "resource_type", "ENERGY", "provider_status", "WAITING"));
        var remote = Map.<String, Object>of("view", "CURRENT", "request", request, "items", steps);
        when(client.request(anyString(), anyMap())).thenReturn(remote);
        assertSame(remote, service.query("resources", Map.of("request_id", "current1", "page", "2", "limit", "20")));
        verify(client).request("resource-orders/query", Map.of("view", "CURRENT", "request_id", "current1"));
        verifyNoMoreInteractions(client);
        verifyNoInteractions(repository, displayNames, totp);
    }

    @Test void resourceQueryRejectsOldGatewayResponsesInsteadOfShowingHistoricalOrders() {
        when(client.request(anyString(), anyMap()))
                .thenReturn(Map.of("items", List.of(Map.of("energy_order_id", "legacy1"))))
                .thenReturn(Map.of("view", "LEGACY", "items", List.of()))
                .thenReturn(Map.of("view", "CURRENT", "items", List.of()));
        for (int attempt = 0; attempt < 2; attempt++) {
            var failure = assertThrows(BusinessException.class, () -> service.query("resources", Map.of()));
            assertEquals("server.compute.payment.invalidResponse", failure.getMessage());
        }
        assertThrows(BusinessException.class, () -> service.query("resources", Map.of("request_id", "current1")));
        verifyNoInteractions(repository, displayNames, totp);
    }

    @Test void historicalResourceIdCannotSilentlyBecomeCurrentListQuery() {
        assertThrows(BusinessException.class, () -> service.query("resources", Map.of("energy_order_id", "legacy1")));
        verifyNoInteractions(client, repository, displayNames, totp);
    }

    @Test void collectionQueryKeepsAllFourAuthoritativeStatusFields() {
        var row = Map.of("batch_id", "batch1", "status", "READY", "credit_status", "CREDITED",
                "display_status", "PROCESSING", "business_status", "READY", "sweep_status", "WAITING", "reason_code", "ENERGY_WAITING");
        when(client.request(anyString(), anyMap())).thenReturn(Map.of("items", List.of(row)));
        assertEquals(List.of(row), service.query("batches", Map.of()).get("items"));
        verify(client).request("collection-batches/query", Map.of("limit", 20, "view", "summary"));
        verifyNoInteractions(repository, displayNames, totp);
    }

    @Test void scopedAdministratorCannotReadMerchantData() {
        doThrow(new BusinessException(403, "server.compute.management.outOfScope")).when(scope).requireGlobalScope();
        assertThrows(BusinessException.class, () -> service.query("wallets", Map.of()));
        verifyNoInteractions(client);
    }

    @Test void ordinaryPermissionNeverSubstitutesForSuperAndTotp() {
        login("61024");
        assertThrows(BusinessException.class, () -> service.requireSuperTotp("123456"));
        verifyNoInteractions(totp, client);
    }

    @Test void invalidOrReplayedTotpPreventsHighRiskRequest() {
        login("super"); when(totp.verify(7, "123456", true)).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.requireSuperTotp("123456"));
        assertThrows(BusinessException.class, () -> service.requireSuperTotp(null));
        verifyNoInteractions(client);
    }

    @Test void feeBalanceRefreshUsesOnlyPublicAddressIdAndDoesNotRequireTotp() {
        when(client.request(anyString(),anyMap())).thenReturn(Map.of("address_id","address_test","items",List.of()));
        assertEquals("address_test",service.refreshFeeAddress("address_test").get("address_id"));
        verify(client).request("service-fee/addresses/refresh",Map.of("address_id","address_test","view","summary"));
        verifyNoInteractions(totp,repository,displayNames);
    }

    @Test void addressFromAnotherMerchantNeverEntersPublicSnapshot() {
        when(client.request(anyString(), anyMap())).thenReturn(Map.of("items",List.of(Map.of("purpose", "SERVICE_FEE", "network_id", "TRON_MAINNET", "merchant_id", "other", "environment", "LIVE", "address_id", "address_test", "address", "TRON_address"))));
        assertThrows(BusinessException.class, service::feeAddresses);
        verifyNoInteractions(repository,displayNames);
    }

    @Test void homepageAddressReadNeverCreatesRemoteAddressAndAlwaysUsesCurrentIdentity() {
        when(client.request("service-fee/addresses/query",Map.of("view","summary"))).thenReturn(Map.of("items",List.of(Map.of("purpose","SERVICE_FEE","merchant_id","merchant_test","environment","LIVE","network_id","TRON_MAINNET","address","saved"))));
        assertEquals(1, ((List<?>) service.feeAddresses().get("items")).size());
        verify(client).request("service-fee/addresses/query",Map.of("view","summary"));
        verify(scope).requireGlobalScope(); verifyNoInteractions(data, totp, repository, displayNames);
    }

    @Test void connectionMetadataDoesNotExposeAnyKeyFileOrKeyMaterial() {
        config.setSigningKeyFile("private/secret.jwk"); config.setCredentialId("credential_test");
        config.setBaseUrl("https://user:password@merchant.example.test/gateway?key=secret#token");
        var result = service.connection();
        assertEquals("credential_test", result.get("credentialId"));
        assertEquals(true, result.get("keysReady"));
        assertFalse(result.toString().contains("secret.jwk"));
        assertEquals("https://merchant.example.test/gateway", result.get("baseUrl"));
        verifyNoInteractions(client);
    }

    @Test void unconfiguredConnectionShowsNoInventedLocalEndpoint() {
        config.setEnabled(false);
        var result = service.connection();
        assertEquals("", result.get("baseUrl"));
        assertEquals(false, result.get("enabled"));
        assertEquals(false, result.get("keysReady"));
        verifyNoInteractions(client);
    }

    @Test void connectionCheckSeparatesAssetsHandshakeFromRequiredWalletAccess() {
        when(client.request("assets/query",Map.of())).thenReturn(Map.of("items",List.of()));
        when(client.request("service-fee/account/query",Map.of("view","summary")))
                .thenThrow(new BusinessException(502,"server.compute.payment.accessDenied"))
                .thenReturn(Map.of("available","100.25","reserved","0"));
        var denied=service.checkConnection();
        assertEquals(true,denied.get("connected")); assertEquals(false,denied.get("ready"));
        assertEquals("DENIED",denied.get("balanceAccess"));
        var ready=service.checkConnection();
        assertEquals(true,ready.get("ready")); assertEquals("READY",ready.get("balanceAccess"));
        verifyNoInteractions(data,totp,repository,displayNames);
    }

    @Test void malformedBalanceResponseDoesNotPassConnectionCapabilityCheck() {
        when(client.request("assets/query",Map.of())).thenReturn(Map.of("items",List.of()));
        when(client.request("service-fee/account/query",Map.of("view","summary"))).thenReturn(Map.of("error",Map.of("code","UNKNOWN")));
        var result=service.checkConnection();
        assertEquals(false,result.get("ready")); assertEquals("FAILED",result.get("balanceAccess"));
        verifyNoInteractions(data,totp,repository,displayNames);
    }

    @Test void recheckPreservesIdempotentBusinessReferenceAndDoesNotPostMoney() {
        login("super"); when(totp.verify(7, "123456", true)).thenReturn(true);
        Map<String, String> input = Map.of("merchant_recheck_id", "MR_same_request", "asset_id", "USDT_TRON", "tx_hash", "chain_hash", "address_id", "address_ref", "code", "123456", "amount", "999999");
        service.recheck(input);
        verify(client).request("deposit-rechecks/create", Map.of("merchant_recheck_id", "MR_same_request", "asset_id", "USDT_TRON", "tx_hash", "chain_hash", "address_id", "address_ref"));
        verifyNoInteractions(data, repository, displayNames);
    }

    @Test void homepageSummaryRetainsEvmNetworksButDropsScannerAndPointsCoinBalances() {
        var network=Map.<String,Object>of("network_id","ETHEREUM_MAINNET","wallet_id","wallet_eth","scan_height",99,
                "balance_items",List.of(Map.of("symbol","ETH","balance","1","balance_status","TRACKING","checked_at","internal")));
        when(client.request("merchant-wallets/query",Map.of("view","summary"))).thenReturn(Map.of("items",List.of(Map.of("group_id","group1","address","0xaddress","networks",List.of(network),"scan_height",99))));
        var wallet=service.query("wallets",Map.of()).toString();
        assertTrue(wallet.contains("ETHEREUM_MAINNET"));assertTrue(wallet.contains("wallet_eth"));assertFalse(wallet.contains("scan_height"));assertFalse(wallet.contains("checked_at"));
        when(client.request("service-fee/addresses/refresh",Map.of("address_id","fee1","view","summary"))).thenReturn(Map.of("address_id","fee1","address","0xaddress","items",List.of(Map.of("symbol","ETH","balance","9")),"scan_height",99));
        var fee=service.refreshFeeAddress("fee1");assertEquals("0xaddress",fee.get("address"));assertFalse(fee.containsKey("items"));assertFalse(fee.containsKey("scan_height"));
    }
    @Test void walletBillsUseActualPageSizeAndOnlyWhitelistedFilters() {
        service.query("bills",Map.of("page","2","page_size","30","direction","OUT","start_time","2026-09-01T00:00:00Z","merchant_id","other"));
        verify(client).request("merchant-bills/query",Map.of("page",2,"page_size",30,"direction","OUT","start_time","2026-09-01T00:00:00Z"));
        verify(scope).requireGlobalScope();verifyNoInteractions(repository,displayNames,totp);
    }

    @Test void failedConfigurationSyncKeepsLastSuccessfulSnapshot() {
        when(client.request("assets/query", Map.of())).thenThrow(new BusinessException("server.compute.payment.timeout"));
        var failure=assertThrows(BusinessException.class, () -> service.synchronize("assets"));
        assertEquals("server.compute.payment.timeout",failure.getMessage());
        var sequence=inOrder(repository,client);
        sequence.verify(repository).synchronizeMerchantSnapshotWrite("merchant_test","LIVE","assets");
        sequence.verify(client).request("assets/query",Map.of());
        sequence.verify(repository).synchronizeMerchantSnapshotWrite3("server.compute.merchant.syncFailed","merchant_test","LIVE","assets");
        sequence.verifyNoMoreInteractions();
        verify(repository,never()).synchronizeMerchantSnapshotWrite2(any(),any(),any(),any());
        verifyNoInteractions(data,displayNames);
    }

    @Test void highRiskControllerMethodsRequireSuperDirectly() throws Exception {
        for (String name : List.of("recheck")) {
            var method = ComputeMerchantController.class.getMethod(name, Map.class);
            assertEquals("hasAuthority('super')", method.getAnnotation(PreAuthorize.class).value());
        }
        assertEquals("hasAuthority('super')", ComputeGatewayAdminController.class.getMethod("reconcile", org.token.model.request.compute.GatewayReconciliationRequest.class).getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAuthority('super')", ComputeMcagController.class.getMethod("cancel", String.class, org.token.model.request.compute.McagCancelRequest.class, JwtUserPrincipal.class).getAnnotation(PreAuthorize.class).value());
    }

    @Test void legacyRecoveryAndCancellationRejectOrdinaryAdminsBeforeGateway() {
        login("610062");
        ComputePaymentGateway gateway = mock(ComputePaymentGateway.class);
        var controller = new ComputeGatewayAdminController(new org.token.service.compute.ComputeGatewayAdminService(gateway, scope, service), mock(ResultFactory.class));
        assertThrows(BusinessException.class, () -> controller.reconcile(new org.token.model.request.compute.GatewayReconciliationRequest("WD_order", "PAYOUT", "核对后恢复", "123456")));
        ComputeMcagPaymentGateway mcag = mock(ComputeMcagPaymentGateway.class);
        var legacy = new ComputeMcagController(new org.token.service.compute.ComputeMcagAdminService(mcag, scope, service), mock(ResultFactory.class));
        assertThrows(BusinessException.class, () -> legacy.cancel("WD_order", new org.token.model.request.compute.McagCancelRequest("撤销未发出的申请", "123456"), mock(JwtUserPrincipal.class)));
        verifyNoInteractions(gateway, mcag, client, totp);
    }

    @Test void legacyRecoveryChecksOneTimeCodeBeforeResumingOriginalOrder() {
        login("super"); when(totp.verify(7, "123456", true)).thenReturn(true);
        ComputePaymentGateway gateway = mock(ComputePaymentGateway.class);
        var controller = new ComputeGatewayAdminController(new org.token.service.compute.ComputeGatewayAdminService(gateway, scope, service), mock(ResultFactory.class));
        controller.reconcile(new org.token.model.request.compute.GatewayReconciliationRequest("WD_order", "PAYOUT", "核对后恢复", "123456"));
        var sequence = inOrder(totp, gateway);
        sequence.verify(totp).verify(7, "123456", true);
        sequence.verify(gateway).requestReconciliation("WD_order", "PAYOUT");
    }
}
