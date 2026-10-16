package com.mcag.core;

import com.mcag.domain.Tenant;
import com.mcag.infrastructure.Db;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.mcag.common.Values.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 查询契约验证全部使用替身，不访问数据库、节点或支付系统。 */
class MerchantQueryContractTest {
    @Test void depositQueryProjectsRealReceiptFieldsAndPreservesTenantAndCursor() {
        var db = mock(Db.class);
        var events = mock(EventService.class);
        var points = mock(ServiceFeeAccountService.class);
        var service = new DepositOrderService(db, events, points);
        when(db.list(anyString(), anyMap())).thenReturn(List.of(map("order_id", "order-2"), map("order_id", "order-1")));

        var result = service.query(new Tenant("merchant-1", "LIVE"), map("merchant_id", "foreign",
                "environment", "SIM", "before_id", "order-3", "limit", 1));
        var sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") var params = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), params.capture());

        assertThat(sql.getValue()).contains("r.address_id", "r.sender_address", "r.transfer_event_id",
                "r.accounting_model", "r.batch_id", "a.decimals", "r.finality_satisfied", "r.risk_status",
                "o.environment=#{p.environment}", "o.merchant_id=#{p.merchant_id}", "order_id=#{p.before}")
                .doesNotContain("SELECT o.*", "o.wallet_id");
        assertThat(params.getValue()).containsEntry("merchant_id", "merchant-1")
                .containsEntry("environment", "LIVE").containsEntry("before", "order-3");
        assertThat(result).containsEntry("has_more", true).doesNotContainKey("next_cursor");
        assertThat((List<?>) result.get("items")).hasSize(1);
        verifyNoMoreInteractions(db);
        verifyNoInteractions(events, points);
    }

    @Test void addressOwnershipCheckIsTenantScopedAcrossEvmChainsAndIncludesHistoricalBindings() {
        var db = mock(Db.class);
        var support = mock(CoreSupport.class);
        when(support.tenant(any())).thenReturn(map("m", "merchant-1", "e", "LIVE"));
        when(db.one(startsWith("SELECT family"), anyMap())).thenReturn(map("family", "EVM"));
        when(db.one(startsWith("SELECT 1 AS registered"), anyMap())).thenReturn(map("registered", 1));
        var gateway = gateway(db, support, mock(MerchantWalletService.class));

        assertThat(gateway.handle(new Tenant("merchant-1", "LIVE"), "customer-addresses/query",
                map("network_id", "POLYGON_MAINNET", "address", "0xAbC"))).containsOnlyKeys("registered")
                .containsEntry("registered", true);
        verify(db).one(argThat(sql -> sql.startsWith("SELECT 1 AS registered")
                && sql.contains("b.merchant_id=#{p.m} AND b.environment=#{p.e}")
                && sql.contains("'CUSTOMER_DEPOSIT','SERVICE_FEE','PAYOUT_PRINCIPAL'")
                && sql.contains("'ETHEREUM_MAINNET','BSC_MAINNET','POLYGON_MAINNET'")
                && sql.contains("lower(b.address)=lower(#{p.address})") && !sql.contains("b.status")),
                argThat(p -> "merchant-1".equals(p.get("m")) && "LIVE".equals(p.get("e"))));
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test void nonEvmOwnershipCheckUsesExactNetworkAndAddressAndCanReturnFalse() {
        var db = mock(Db.class);
        var support = mock(CoreSupport.class);
        when(support.tenant(any())).thenReturn(map("m", "merchant-1", "e", "LIVE"));
        when(db.one(startsWith("SELECT family"), anyMap())).thenReturn(map("family", "TRON"));
        when(db.one(startsWith("SELECT 1 AS registered"), anyMap())).thenReturn(null);
        var gateway = gateway(db, support, mock(MerchantWalletService.class));

        assertThat(gateway.handle(new Tenant("merchant-1", "LIVE"), "customer-addresses/query",
                map("network_id", "TRON_MAINNET", "address", "TAbC"))).containsEntry("registered", false);
        verify(db).one(argThat(sql -> sql.startsWith("SELECT 1 AS registered")
                && sql.contains("b.network_id=#{p.network} AND b.address=#{p.address}") && !sql.contains("lower(")), anyMap());
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test void refreshSummaryStillDelegatesOnlyToExistingTrackedBalanceQuery() {
        var db = mock(Db.class);
        var wallets = mock(MerchantWalletService.class);
        when(wallets.refreshBalances("merchant-1", "wallet-1")).thenReturn(map("wallet_id", "wallet-1",
                "network_id", "TRON_MAINNET", "items", List.of(map("asset_id", "TRX_TRON",
                        "balance", BigDecimal.TEN, "balance_status", "TRACKING", "scan_height", 42L)), "scan_height", 42L));
        var result = gateway(db, mock(CoreSupport.class), wallets).handle(new Tenant("merchant-1", "LIVE"),
                "merchant-wallets/refresh", map("wallet_id", "wallet-1", "view", "summary"));
        assertThat(result).containsEntry("wallet_id", "wallet-1").doesNotContainKey("scan_height");
        verify(wallets).refreshBalances("merchant-1", "wallet-1");
        verifyNoMoreInteractions(wallets);
        verifyNoInteractions(db);
    }

    @Test void assetMetadataQueryUsesOnlyExistingPublicColumnsAndMerchantVisibility() throws Exception {
        var db = mock(Db.class);
        var support = mock(CoreSupport.class);
        when(support.tenant(any())).thenReturn(map("m", "merchant-1", "e", "LIVE"));
        when(db.list(anyString(), anyMap())).thenReturn(List.of(map("asset_id", "USDC_BSC",
                "decimals", 18, "network_name", "BNB Smart Chain", "family", "EVM", "native_asset_id", "BNB_BSC")));
        var gateway = gateway(db, support, mock(MerchantWalletService.class));
        var valuation = mock(ValuationService.class);
        when(valuation.limits()).thenReturn(Map.of());
        var field = GatewayService.class.getDeclaredField("valuation");
        field.setAccessible(true);field.set(gateway, valuation);

        gateway.handle(new Tenant("merchant-1", "LIVE"), "assets/query", Map.of());
        verify(db).list(argThat(sql -> sql.contains("a.contract_address,a.token_standard")
                && sql.contains("n.display_name AS network_name,n.family,n.chain_id")
                && sql.contains("n.native_asset_id,na.symbol AS native_symbol,na.decimals AS native_decimals")
                && sql.contains("p.merchant_id=#{p.m} AND p.environment=#{p.e} AND p.visible AND n.enabled")
                && !sql.contains("rpc_endpoints") && !sql.contains("key_ref")), anyMap());
        verify(db, never()).update(anyString(), anyMap());
    }

    private static GatewayService gateway(Db db, CoreSupport support, MerchantWalletService wallets) {
        return new GatewayService(db, support, mock(AddressService.class), mock(PayoutService.class),
                mock(CollectionBatchService.class), mock(CollectionPolicyService.class), wallets,
                mock(ServiceFeeAccountService.class), mock(EnergyResourceService.class), mock(MerchantNotificationService.class));
    }
}
