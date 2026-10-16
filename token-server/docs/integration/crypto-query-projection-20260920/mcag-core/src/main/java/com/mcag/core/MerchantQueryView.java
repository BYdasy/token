package com.mcag.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 商户首页的可选白名单视图，不改变默认查询、余额计算或刷新语义。 */
final class MerchantQueryView {
    private static final List<String> ADDRESS_FIELDS = List.of(
            "merchant_id", "environment", "purpose", "address_id", "group_id", "evm_group_id",
            "wallet_id", "source_wallet_id", "network_id", "address", "collection_address",
            "label", "wallet_type", "enabled", "status", "monitoring_ready", "initialization_status",
            "is_primary", "historical_address");
    private static final List<String> BALANCE_FIELDS = List.of(
            "asset_id", "symbol", "balance", "confirmed_balance", "balance_status");

    private MerchantQueryView() { }

    static Map<String, Object> apply(String operation, Map<String, Object> request, Map<String, Object> response) {
        if (!"summary".equals(request.get("view"))) return response;
        return switch (operation) {
            case "merchant-wallets/query", "collection-destinations/query" -> rows(response, true);
            case "merchant-wallets/refresh", "collection-destinations/refresh" -> address(response, true);
            case "service-fee/addresses/query" -> rows(response, false);
            case "service-fee/addresses/refresh" -> address(response, false);
            case "service-fee/account/query" -> pick(response, List.of("available", "reserved", "unit"));
            default -> response;
        };
    }

    private static Map<String, Object> rows(Map<String, Object> response, boolean balances) {
        var result = new LinkedHashMap<String, Object>();
        var items = new ArrayList<Map<String, Object>>();
        if (response.get("items") instanceof List<?> rows)
            for (Object row : rows) if (row instanceof Map<?, ?> values) items.add(address(values, balances));
        result.put("items", items);
        return result;
    }

    private static Map<String, Object> address(Map<?, ?> source, boolean balances) {
        var result = pick(source, ADDRESS_FIELDS);
        if (source.get("networks") instanceof List<?> networks) {
            var items = new ArrayList<Map<String, Object>>();
            for (Object network : networks)
                if (network instanceof Map<?, ?> values) items.add(address(values, balances));
            result.put("networks", items);
        }
        if (balances) {
            for (String key : List.of("balance_items", "items")) {
                if (!(source.get(key) instanceof List<?> entries)) continue;
                var items = new ArrayList<Map<String, Object>>();
                for (Object entry : entries)
                    if (entry instanceof Map<?, ?> values) items.add(pick(values, BALANCE_FIELDS));
                result.put(key, items);
            }
        }
        return result;
    }

    private static Map<String, Object> pick(Map<?, ?> source, List<String> fields) {
        var result = new LinkedHashMap<String, Object>();
        for (String field : fields) if (source.containsKey(field)) result.put(field, source.get(field));
        return result;
    }
}
