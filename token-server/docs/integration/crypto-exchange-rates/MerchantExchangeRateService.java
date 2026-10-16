package com.mcag.core;

import com.mcag.common.BusinessException;
import com.mcag.domain.Tenant;
import com.mcag.infrastructure.Db;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.mcag.common.Values.map;

/** 商户行情只读投影：复用已成功采集的价格快照，不查链、不调用供应商、不生成新报价。 */
@Service
public class MerchantExchangeRateService {
    private final Db db;
    public MerchantExchangeRateService(Db db) { this.db=db; }

    public Map<String,Object> query(Tenant tenant,Map<String,Object> body) {
        if (!body.keySet().equals(Set.of("asset_ids")) || !(body.get("asset_ids") instanceof List<?> requested)
                || requested.isEmpty() || requested.size()>100)
            throw new BusinessException("INVALID_ARGUMENT","asset_ids 必须为 1 到 100 个资产编号");
        Set<String> ids=new LinkedHashSet<>();
        for(Object value:requested) {
            if(!(value instanceof String id)||!id.matches("[A-Za-z0-9_.:-]{1,128}")||!ids.add(id))
                throw new BusinessException("INVALID_ARGUMENT","资产编号格式无效或重复");
        }
        var args=map("m",tenant.merchantId(),"e",tenant.environment());
        List<String> parameters=new ArrayList<>();int index=0;
        for(String id:ids) {String key="asset"+index++;args.put(key,id);parameters.add("#{p."+key+"}");}
        var rows=db.list("""
                SELECT a.asset_id,a.symbol,a.network_id,s.snapshot_id,s.usdt_price,s.source,s.observed_at,s.expires_at
                  FROM assets a JOIN merchant_asset_policies p ON p.asset_id=a.asset_id
                  JOIN networks n ON n.network_id=a.network_id
                  LEFT JOIN service_fee_price_policies f ON f.asset_id=a.asset_id AND f.enabled
                  LEFT JOIN LATERAL (
                    SELECT snapshot_id,usdt_price,source,observed_at,expires_at
                      FROM service_fee_price_snapshots
                     WHERE asset_id=a.asset_id AND price_policy_version=f.configuration_version
                       AND source=f.price_source AND source IN ('COINGECKO','USDT_UNIT')
                     ORDER BY observed_at DESC,created_at DESC LIMIT 1
                  ) s ON true
                 WHERE p.merchant_id=#{p.m} AND p.environment=#{p.e} AND p.visible AND n.enabled
                   AND a.asset_id IN (
                """+String.join(",",parameters)+") ORDER BY a.asset_id",args);
        Instant now=Instant.now();
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            // 显式白名单，商户无权访问其他商户资产、供应商密钥或内部策略配置。
            var item=map("asset_id",row.get("asset_id"),"symbol",row.get("symbol"),"network_id",row.get("network_id"));
            boolean valid=false;
            if(row.get("snapshot_id")!=null && row.get("usdt_price")!=null && row.get("observed_at")!=null && row.get("expires_at")!=null) {
                var observed=ServiceFeePricingService.instant(row.get("observed_at"));
                var expires=ServiceFeePricingService.instant(row.get("expires_at"));
                var rate=new BigDecimal(row.get("usdt_price").toString());
                valid=rate.signum()>0&&!observed.isAfter(now)&&!observed.isBefore(now.minusSeconds(600))&&expires.isAfter(now);
                item.putAll(map("snapshot_id",row.get("snapshot_id"),"usdt_price",rate.toPlainString(),"source",row.get("source"),
                        "observed_at",observed.toString(),"expires_at",expires.toString()));
            }
            item.put("status",valid?"AVAILABLE":row.get("snapshot_id")==null?"MISSING":"STALE");
            result.add(item);
        }
        return map("quote_currency","USDT","items",result,"checked_at",now.toString());
    }
}
