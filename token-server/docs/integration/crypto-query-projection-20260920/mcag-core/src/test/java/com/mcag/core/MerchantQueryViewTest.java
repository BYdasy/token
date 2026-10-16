package com.mcag.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.mcag.common.Values.map;
import static org.assertj.core.api.Assertions.assertThat;

/** 首页摘要只收缩返回数据，不改变原结果、未知余额或逐链状态。 */
class MerchantQueryViewTest {
    @Test void groupedWalletSummaryKeepsChainIdentityAndUnknownBalanceButHidesInternalFields() {
        var balance = map("asset_id", "USDC_BSC", "symbol", "USDC", "balance", null,
                "confirmed_balance", null, "balance_status", "INITIALIZING", "scan_height", 123L,
                "checked_at", "2026-09-20T00:00:00Z", "review_id", "check-1");
        var network = map("wallet_id", "wallet-bsc", "network_id", "BSC_MAINNET", "address", "0x123",
                "status", "INITIALIZING", "monitoring_ready", false, "initialization_status", "PENDING",
                "next_attempt_at", "later", "initialization_error", "internal-error", "balance_items", List.of(balance));
        var wallet = map("group_id", "group-1", "wallet_id", "wallet-eth", "source_wallet_id", "wallet-eth",
                "network_id", "EVM", "address", "0x123", "networks", List.of(network),
                "key_ref", "must-not-return", "pool_last_used_at", "earlier");
        var original = map("items", List.of(wallet), "scan_height", 123L);

        var summary = MerchantQueryView.apply("merchant-wallets/query", map("view", "summary"), original);
        var projected = item(summary, "items");
        var chain = item(projected, "networks");
        var asset = item(chain, "balance_items");

        assertThat(projected).containsEntry("group_id", "group-1").containsEntry("wallet_id", "wallet-eth")
                .doesNotContainKeys("key_ref", "pool_last_used_at");
        assertThat(chain).containsEntry("wallet_id", "wallet-bsc").containsEntry("monitoring_ready", false)
                .doesNotContainKeys("next_attempt_at", "initialization_error");
        assertThat(asset).containsEntry("balance", null).containsEntry("balance_status", "INITIALIZING")
                .doesNotContainKeys("scan_height", "checked_at", "review_id");
        assertThat(wallet).containsKey("key_ref");
        assertThat(network).containsKey("initialization_error");
    }

    @Test void pointsSummaryNeverIncludesCoinBalancesOrAccountPolicies() {
        var row = map("address_id", "address-1", "group_id", "group-1", "network_id", "BSC_MAINNET",
                "address", "0x123", "status", "ACTIVE", "monitoring_ready", true,
                "balance_items", List.of(map("balance", BigDecimal.TEN)), "scan_height", 123L);
        var summary = MerchantQueryView.apply("service-fee/addresses/query", map("view", "summary"), map("items", List.of(row)));
        assertThat(item(summary, "items")).containsEntry("address_id", "address-1")
                .doesNotContainKeys("balance_items", "scan_height");
        row.put("items", List.of(map("balance", BigDecimal.TEN)));
        assertThat(MerchantQueryView.apply("service-fee/addresses/refresh", map("view", "summary"), row))
                .containsEntry("status", "ACTIVE").doesNotContainKeys("items", "balance_items", "scan_height");
        assertThat(MerchantQueryView.apply("service-fee/account/query", map("view", "summary"),
                map("available", "20.5", "reserved", "1", "unit", "USDT_POINT", "policy", map("internal", true))))
                .containsOnlyKeys("available", "reserved", "unit");
    }

    @Test void normalQueriesAndBusinessResponsesRemainUntouched() {
        var response = map("items", List.of(map("scan_height", 123L)));
        assertThat(MerchantQueryView.apply("merchant-wallets/query", Map.of(), response)).isSameAs(response);
        assertThat(MerchantQueryView.apply("payouts/query", map("view", "summary"), response)).isSameAs(response);
        assertThat(MerchantQueryView.apply("deposit-orders/query", map("view", "summary"), response)).isSameAs(response);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> source, String key) {
        return (Map<String, Object>) ((List<?>) source.get(key)).get(0);
    }
}
