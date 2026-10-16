package org.token.service.compute;

import org.token.exception.BusinessException;
import java.util.*;

/** 首页响应只保留展示与刷新所需字段，不能透传第三方内部扫描和核查数据。 */
final class ComputeMerchantView {
    private static final List<String> ADDRESS=List.of("merchant_id","environment","purpose","address_id","group_id","evm_group_id",
            "wallet_id","source_wallet_id","network_id","address","collection_address","label","wallet_type","enabled","status","monitoring_ready");
    private static final List<String> BALANCE=List.of("asset_id","symbol","balance","confirmed_balance","balance_status");
    private ComputeMerchantView() { }

    static Map<String,Object> account(Map<String,Object> input) { return pick(input,List.of("available","reserved","unit")); }
    static Map<String,Object> filterOptions(Map<String,Object> input) {
        if(input==null||!(input.get("items") instanceof List<?> rows))throw invalid();
        return Map.of("items",rows.stream().map(value->{
            if(!(value instanceof Map<?,?> row))throw invalid();
            for(String key:List.of("asset_id","network_id","symbol"))
                if(!(row.get(key) instanceof String text)||text.isBlank()||text.length()>128)throw invalid();
            return pick(row,List.of("asset_id","network_id","symbol","network_name"));
        }).toList());
    }
    static Map<String,Object> energyAccount(Map<String,Object> input) {
        if(input==null||!Set.of("GLOBAL","INDEPENDENT").contains(Objects.toString(input.get("mode"),""))
                ||!Set.of("NOT_CONFIGURED","DISABLED","UNAVAILABLE","UNINITIALIZED","STALE","READY").contains(Objects.toString(input.get("status"),""))
                ||!(input.get("configured") instanceof Boolean)||!(input.get("enabled") instanceof Boolean))throw invalid();
        for(String key:List.of("balance_trx","balance_usdt")) {
            Object value=input.get(key);
            if(value!=null&&(!(value instanceof String text)||!text.matches("[0-9]{1,30}(?:\\.[0-9]{1,18})?")))throw invalid();
        }
        return pick(input,List.of("mode","provider_id","configured","name","enabled","recharge_address",
                "balance_trx","balance_usdt","last_sync_success_at","status"));
    }
    static Map<String,Object> rows(Map<String,Object> input,boolean balance) {
        if(!(input.get("items") instanceof List<?> rows))throw invalid();
        return Map.of("items",rows.stream().map(value->{if(!(value instanceof Map<?,?> row))throw invalid();return address(row,balance);}).toList());
    }
    static Map<String,Object> address(Map<?,?> input,boolean balance) {
        var result=pick(input,ADDRESS);
        if(input.get("networks") instanceof List<?> networks) result.put("networks",networks.stream().map(value->{if(!(value instanceof Map<?,?> row))throw invalid();return address(row,balance);}).toList());
        if(balance) for(String key:List.of("balance_items","items")) if(input.get(key) instanceof List<?> rows)
            result.put(key,rows.stream().map(value->{if(!(value instanceof Map<?,?> row))throw invalid();return pick(row,BALANCE);}).toList());
        return result;
    }
    private static Map<String,Object> pick(Map<?,?> input,List<String> keys) {
        var result=new LinkedHashMap<String,Object>();for(String key:keys)if(input.containsKey(key))result.put(key,input.get(key));return result;
    }
    private static BusinessException invalid(){return new BusinessException(502,"server.compute.payment.invalidResponse");}
}
