package com.mcag.core;

import com.mcag.common.Amounts;
import com.mcag.common.BusinessException;
import com.mcag.common.Ids;
import com.mcag.domain.Tenant;
import com.mcag.infrastructure.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static com.mcag.common.Values.*;

/**
 * Authenticated merchant surface. Tenant always comes from verified credentials, never request body.
 */
@Service
public class GatewayService {
    private final Db db;
    @org.springframework.beans.factory.annotation.Autowired private DepositOrderService depositOrders;
    @org.springframework.beans.factory.annotation.Autowired private DepositRecheckService rechecks;
    @org.springframework.beans.factory.annotation.Autowired private MerchantBillService bills;
    @org.springframework.beans.factory.annotation.Autowired private ValuationService valuation;
    @org.springframework.beans.factory.annotation.Autowired private PointsQueryService points;
    @org.springframework.beans.factory.annotation.Autowired private MerchantExchangeRateService exchangeRates;
    private final CoreSupport support;
    private final AddressService addresses;
    private final PayoutService payouts;
    private final CollectionBatchService batches;
    private final CollectionPolicyService collectionPolicies;
    private final MerchantWalletService merchantWallets;
    private final ServiceFeeAccountService serviceFees;
    private final EnergyResourceService energy;
    private final MerchantNotificationService notifications;

    public GatewayService(Db db, CoreSupport support, AddressService addresses, PayoutService payouts, CollectionBatchService batches, CollectionPolicyService collectionPolicies, MerchantWalletService merchantWallets, ServiceFeeAccountService serviceFees, EnergyResourceService energy, MerchantNotificationService notifications) {
        this.db = db;
        this.support = support;
        this.addresses = addresses;
        this.payouts = payouts;
        this.batches=batches;this.collectionPolicies=collectionPolicies;this.merchantWallets=merchantWallets;
        this.serviceFees=serviceFees;this.energy=energy;this.notifications=notifications;
    }

    @Transactional
    public Map<String, Object> handle(Tenant tenant, String operation, Map<String, Object> body) {
        support.merchant(tenant);
        for (String forbidden : List.of("merchant_id", "environment", "contract_address", "decimals", "token_standard", "source_wallet", "raw_transaction"))
            if (body.containsKey(forbidden))
                throw new BusinessException("INVALID_ARGUMENT", "Field is platform-controlled: " + forbidden);
        var result = switch (operation) {
            case "customer-addresses/get-or-create" -> customerAddress(tenant, body);
            case "customer-addresses/query" -> registeredAddress(tenant, body);
            case "deposit-orders/query" -> depositOrders.query(tenant,body);
            case "collection-batches/query" -> batches.query(tenant, body);
            case "merchant-wallets/query" -> merchantWallets.wallets(tenant.merchantId(), text(body,"network_id",null));
            case "merchant-wallets/refresh" -> merchantWallets.refreshBalances(tenant.merchantId(), required(body,"wallet_id"));
            case "collection-destinations/query" -> collectionDestinations(tenant,body);
            case "collection-destinations/refresh" -> merchantWallets.refreshCollection(tenant.merchantId(),required(body,"network_id"));
            case "merchant-policies/query" -> collectionPolicies.list(tenant.merchantId());
            case "service-fee/account/query" -> serviceFees.summary(tenant);
            case "service-fee/ledger/query" -> serviceFees.ledger(tenant,page(body),CoreSupport.limit(body),stringFilters(body));
            case "service-fee/addresses/query" -> points.addresses(tenant,text(body,"network_id",null),false);
            case "service-fee/addresses/refresh" -> points.balance(tenant,required(body,"address_id"));
            case "resource-orders/query" -> energy.merchantOrders(tenant,body);
            case "notifications/query" -> notifications.query(tenant,page(body),CoreSupport.limit(body));
            case "notifications/attempts/query" -> notifications.attempts(tenant,required(body,"delivery_id"));
            case "sweeps/query" -> sweepQuery(tenant,body);
            case "payouts/quote" -> payouts.quote(tenant, body);
            case "payouts/create" -> payouts.create(tenant, body);
            case "payouts/query" -> payoutQuery(tenant, body);
            case "merchant-bills/query" -> merchantBills(tenant,body);
            case "deposit-rechecks/create" -> rechecks.create(tenant, body, null);
            case "deposit-rechecks/query" ->
                    query(tenant, "deposit_rechecks", body, "recheck_id", "merchant_recheck_id", "created_at");
            case "assets/query" -> assets(tenant);
            case "exchange-rates/query" -> exchangeRates.query(tenant,body);
            case "events/query" -> events(tenant, body);
            default -> throw new BusinessException("NOT_FOUND", "Unknown merchant operation");
        };
        return MerchantQueryView.apply(operation, body, result);
    }

    /** 仅核对本商户已有收款地址，历史绑定仍保留归属，不泄露用户身份或钱包信息。 */
    private Map<String,Object> registeredAddress(Tenant tenant, Map<String,Object> body) {
        for (String field : body.keySet())
            if (!Set.of("network_id", "address").contains(field))
                throw new BusinessException("INVALID_ARGUMENT", "地址查询参数无效");
        String network = required(body, "network_id"), address = required(body, "address").trim();
        if (network.length() > 80 || address.isBlank() || address.length() > 256)
            throw new BusinessException("INVALID_ARGUMENT", "地址查询参数无效");
        var params = support.tenant(tenant);
        params.put("network", network);params.put("address", address);
        var chain = db.one("SELECT family FROM networks WHERE network_id=#{p.network}", params);
        if (chain == null) throw new BusinessException("NOT_FOUND", "网络不存在");
        boolean sharedEvm = "EVM".equals(chain.get("family"))
                && Set.of("ETHEREUM_MAINNET", "BSC_MAINNET", "POLYGON_MAINNET").contains(network);
        String addressMatch = sharedEvm
                ? "b.network_id IN ('ETHEREUM_MAINNET','BSC_MAINNET','POLYGON_MAINNET') AND lower(b.address)=lower(#{p.address})"
                : "b.network_id=#{p.network} AND b.address=#{p.address}";
        var binding = db.one("SELECT 1 AS registered FROM address_bindings b WHERE b.merchant_id=#{p.m} AND b.environment=#{p.e} AND b.purpose IN ('CUSTOMER_DEPOSIT','SERVICE_FEE','PAYOUT_PRINCIPAL') AND " + addressMatch + " LIMIT 1", params);
        return map("registered", binding != null);
    }

    private Map<String,Object> merchantBills(Tenant tenant,Map<String,Object> body) {
        var filters=new LinkedHashMap<String,String>();
        for(String key:List.of("network_id","asset_id","wallet_id","tx_hash","direction","bill_type","status","address","start_time","end_time","page","page_size"))
            if(body.get(key)!=null)filters.put(key,body.get(key).toString());
        filters.put("merchant_id",tenant.merchantId());return bills.query(filters);
    }

    private static Map<String,String> stringFilters(Map<String,Object> body) {var filters=new LinkedHashMap<String,String>();body.forEach((k,v)->{if(v!=null)filters.put(k,v.toString());});return filters;}

    private static int page(Map<String,Object> body) {
        try {int page=Integer.parseInt(Objects.toString(body.getOrDefault("page",1)));if(page<1||page>100000) throw new NumberFormatException();return page;}
        catch(RuntimeException ex) {throw new BusinessException("INVALID_ARGUMENT","page 必须在 1 到 100000 之间");}
    }
    private Map<String,Object> collectionDestinations(Tenant t,Map<String,Object> body) {
        var p=support.tenant(t);String filter="";
        if(body.get("network_id")!=null){p.put("n",required(body,"network_id"));filter=" AND network_id=#{p.n}";}
        return map("items",db.list("SELECT network_id,collection_address,enabled,version,updated_at FROM merchant_network_settings WHERE merchant_id=#{p.m} AND environment=#{p.e}"+filter+" ORDER BY network_id",p));
    }
    private Map<String,Object> sweepQuery(Tenant t,Map<String,Object> body) {
        var p=support.tenant(t);p.put("limit",CoreSupport.limit(body)+1);String filter="";
        for(String k:List.of("batch_id","network_id","asset_id","status","tx_hash")) if(body.get(k)!=null){p.put(k,required(body,k));filter+=" AND "+k+"=#{p."+k+"}";}
        if(body.get("address")!=null){p.put("address",required(body,"address"));filter+=" AND (destination_address=#{p.address} OR source_wallet_id IN(SELECT wallet_id FROM wallets WHERE address=#{p.address} AND network_id=custody_operations.network_id))";}
        var timeFilters=new StringBuilder();MerchantBillService.timeFilter(timeFilters,p,"created_at",stringFilters(body));filter+=timeFilters;
        if(body.get("before_id")!=null){p.put("before",required(body,"before_id"));filter+=" AND (created_at,operation_id)<(SELECT created_at,operation_id FROM custody_operations WHERE operation_id=#{p.before} AND merchant_id=#{p.m} AND environment=#{p.e})";}
        var rows=db.list("SELECT operation_id,batch_id,network_id,asset_id,operation_type,destination_address,amount,status,tx_hash,actual_fee_native,reason_code,created_at,updated_at FROM custody_operations WHERE merchant_id=#{p.m} AND environment=#{p.e} AND operation_type IN ('SWEEP','GAS_TOPUP')"+filter+" ORDER BY created_at DESC,operation_id DESC LIMIT #{p.limit}",p);
        int limit=CoreSupport.limit(body);boolean more=rows.size()>limit;if(more) rows=new ArrayList<>(rows.subList(0,limit));
        return map("items",rows,"has_more",more,"next_cursor",rows.isEmpty()?null:rows.get(rows.size()-1).get("operation_id"));
    }

    private Map<String, Object> customerAddress(Tenant t, Map<String, Object> b) {
        String network = network(t, b);
        return addresses.getOrCreate(t, required(b, "customer_id"), network, "CUSTOMER_DEPOSIT");
    }

    private String network(Tenant t, Map<String, Object> b) {
        String n = text(b, "network_id", null);
        if (b.get("asset_id") != null) {
            var a = support.asset(t, required(b, "asset_id"), "deposit");
            String expected = a.get("network_id").toString();
            if (n != null && !expected.equals(n))
                throw new BusinessException("ASSET_NETWORK_MISMATCH", "Asset and network differ");
            return expected;
        }
        return required(b, "network_id");
    }

    private Map<String, Object> assets(Tenant t) {
        var rows = db.list("""
                SELECT a.asset_id,a.symbol,a.network_id,a.asset_type,a.decimals,
                       a.contract_address,a.token_standard,n.display_name AS network_name,n.family,n.chain_id,
                       n.native_asset_id,na.symbol AS native_symbol,na.decimals AS native_decimals,
                       n.finality_type,n.required_confirmations,n.configuration_version AS network_configuration_version,
                       (a.deposit_enabled AND p.deposit_enabled AND n.deposit_enabled) AS deposit_enabled,
                       (a.payout_enabled AND p.payout_enabled AND n.payout_enabled) AS payout_enabled,
                       COALESCE(d.credit_threshold,a.minimum_deposit) AS minimum_deposit,
                       COALESCE(d.ignore_amount,CASE WHEN a.symbol='USDT' THEN 0.1 ELSE 0 END) AS ignore_amount,COALESCE(d.sweep_threshold,0) AS sweep_threshold,
                       COALESCE(p.minimum_payout_override,a.minimum_payout) AS minimum_payout,
                       0 AS maximum_payout,
                       a.platform_fee,a.network_fee_charge_cap,a.configuration_version,
                       m.valuation_currency,s.enabled AS internal_merchant_enabled,
                       c.execution_enabled AS internal_global_execution,
                       COALESCE(s.execution_enabled,c.execution_enabled,false) AS internal_merchant_execution,
                       COALESCE(s.payout_enabled,c.payout_enabled,false) AS internal_merchant_payout,
                       COALESCE(s.auto_approve_payouts,c.auto_approve_payouts,false) AS internal_auto_approve,
                       COALESCE(s.max_network_fee_native,c.max_network_fee_native,0) AS internal_merchant_fee_cap,
                       EXISTS(SELECT 1 FROM wallets w JOIN wallet_custody_keys k ON k.wallet_id=w.wallet_id AND k.network_id=w.network_id
                               WHERE w.merchant_id=p.merchant_id AND w.environment=p.environment AND w.network_id=a.network_id
                                 AND w.wallet_type='HOT' AND w.status='ACTIVE'
                                 AND NOT EXISTS(SELECT 1 FROM address_bindings b WHERE b.wallet_id=w.wallet_id)) AS internal_pool_ready
                  FROM assets a JOIN merchant_asset_policies p ON p.asset_id=a.asset_id
                  JOIN merchants m ON m.merchant_id=p.merchant_id AND m.environment=p.environment
                  JOIN networks n ON n.network_id=a.network_id
                  LEFT JOIN assets na ON na.asset_id=n.native_asset_id AND na.network_id=n.network_id
                  LEFT JOIN collection_asset_defaults d ON d.asset_id=a.asset_id
                  LEFT JOIN custody_network_policies c ON c.network_id=n.network_id
                  LEFT JOIN merchant_network_settings s ON s.merchant_id=p.merchant_id AND s.environment=p.environment AND s.network_id=a.network_id
                 WHERE p.merchant_id=#{p.m} AND p.environment=#{p.e} AND p.visible AND n.enabled
                 ORDER BY a.network_id,a.asset_id
                """, support.tenant(t));
        rows.forEach(GatewayService::describeAssetFunding);
        var limits=valuation.limits();
        for(var row:rows) {
            row.put("single_payout_review_limit_usdt",limits.get("single_payout_limit_usdt"));
            row.put("daily_payout_review_limit_usdt",limits.get("daily_payout_limit_usdt"));
            row.put("daily_limit_timezone","UTC"); row.put("valuation_max_age_seconds",600);
        }
        return map("items", rows);
    }

    /** 展示审核阈值与实际收费，避免调用方把审核门槛误当成拒绝出款的硬上限。 */
    static void describeAssetFunding(Map<String, Object> row) {
        boolean ready=Boolean.TRUE.equals(row.get("payout_enabled")) && Boolean.TRUE.equals(row.get("internal_global_execution"))
                && Boolean.TRUE.equals(row.get("internal_merchant_enabled"))
                && Boolean.TRUE.equals(row.get("internal_merchant_execution"))
                && Boolean.TRUE.equals(row.get("internal_merchant_payout"))
                && Boolean.TRUE.equals(row.get("internal_pool_ready"))
                && Amounts.value(row.get("internal_merchant_fee_cap")).signum()>0;
        row.put("maximum_payout",BigDecimal.ZERO);
        row.put("maximum_payout_action","REVIEW");
        row.put("platform_fee",BigDecimal.ZERO);row.put("network_fee_charge_cap",BigDecimal.ZERO);
        row.put("fee_policy","MERCHANT_POOL_NO_ADDITIONAL_SERVICE_FEE");
        row.put("automatic_payout_enabled",ready&&Boolean.TRUE.equals(row.get("internal_auto_approve")));
        row.put("payout_enabled",ready);row.put("accounting_model","MERCHANT_POOL");row.put("payout_funding","MERCHANT_WALLET_POOL");
        row.keySet().removeIf(key -> key.startsWith("internal_"));
    }

    /**
     * 商户出款查询只投影公开字段；来源钱包取不可变签名交易，不能使用当前热钱包配置。
     */
    private Map<String, Object> payoutQuery(Tenant t, Map<String, Object> b) {
        int limit = CoreSupport.limit(b);
        var p = support.tenant(t);
        p.put("limit", limit + 1);
        StringBuilder sql = new StringBuilder("""
                SELECT p.payout_id,p.merchant_id,p.environment,p.merchant_order_id,p.customer_id,p.quote_id,
                       p.asset_id,p.network_id,p.amount,p.destination_address,p.destination_memo,p.status,
                       p.safe_to_release_user_principal,p.resource_version,p.reason_code,p.accounting_model,
                       p.created_at,p.updated_at,p.completed_at,p.review_reason,
                       p.valuation_usdt_amount,p.valuation_currency,p.valuation_amount,p.valuation_rate,
                       p.valuation_observed_at,p.valuation_expires_at,p.valuation_day,
                       q.platform_fee,q.network_fee_charge_cap,q.max_fee_charge,p.asset_id AS fee_asset_id,
                       a.symbol,a.decimals,t.tx_hash,w.address AS sender_address,
                       p.destination_address AS receiver_address,t.status AS chain_status,
                       t.broadcast_at,t.confirmed_at,o.status AS custody_status,
                       o.actual_fee_native,n.native_asset_id AS network_fee_asset_id,
                       o.current_confirmations,n.required_confirmations,o.finality_satisfied,o.confirmation_checked_at,o.observed_block_hash
                  FROM payouts p
                  LEFT JOIN payout_quotes q ON q.quote_id=p.quote_id
                       AND q.merchant_id=p.merchant_id AND q.environment=p.environment
                       AND q.asset_id=p.asset_id AND q.network_id=p.network_id
                  LEFT JOIN payout_transactions t ON t.payout_id=p.payout_id
                       AND t.merchant_id=p.merchant_id AND t.environment=p.environment AND t.network_id=p.network_id
                  LEFT JOIN wallets w ON w.wallet_id=t.wallet_id
                       AND w.environment=p.environment AND w.network_id=p.network_id
                  LEFT JOIN custody_operations o ON o.payout_id=p.payout_id AND o.operation_type='PAYOUT'
                       AND o.merchant_id=p.merchant_id AND o.environment=p.environment
                       AND o.asset_id=p.asset_id AND o.network_id=p.network_id
                  LEFT JOIN assets a ON a.asset_id=p.asset_id AND a.network_id=p.network_id
                  LEFT JOIN networks n ON n.network_id=p.network_id
                 WHERE p.merchant_id=#{p.m} AND p.environment=#{p.e}
                """);
        for (String key : List.of("payout_id", "merchant_order_id", "asset_id", "network_id", "customer_id")) {
            if (b.get(key) != null) {
                p.put(key, required(b, key));
                sql.append(" AND p.").append(key).append("=#{p.").append(key).append("}");
            }
        }
        if (b.get("before_id") != null) {
            p.put("before", required(b, "before_id"));
            sql.append(" AND (p.created_at,p.payout_id) < (SELECT cursor.created_at,cursor.payout_id FROM payouts cursor WHERE cursor.payout_id=#{p.before} AND cursor.merchant_id=#{p.m} AND cursor.environment=#{p.e})");
        }
        sql.append(" ORDER BY p.created_at DESC,p.payout_id DESC LIMIT #{p.limit}");
        var rows = db.list(sql.toString(), p);
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        // 确认数来自后台实际链观测；尚未观测时为 null，查询接口不临时逐条查链。
        return map("items", rows, "has_more", more, "next_cursor", rows.isEmpty() ? null : rows.get(rows.size() - 1).get("payout_id"));
    }

    private Map<String, Object> query(Tenant t, String table, Map<String, Object> b, String primary, String secondary, String time) {
        var p = support.tenant(t);
        p.put("limit", CoreSupport.limit(b) + 1);
        StringBuilder sql = new StringBuilder("SELECT * FROM " + table + " WHERE merchant_id=#{p.m} AND environment=#{p.e}");
        for (String key : List.of(primary, secondary, "asset_id", "network_id", "customer_id")) {
            if (b.get(key) != null) {
                if ("ledger_entries".equals(table) && Set.of("asset_id", "network_id", "customer_id").contains(key))
                    continue;
                p.put(key, required(b, key));
                sql.append(" AND ").append(key).append("=#{p.").append(key).append("}");
            }
        }
        if (b.get("before_id") != null) {
            p.put("before", required(b, "before_id"));
            sql.append(" AND (").append(time).append(",").append(primary).append(") < (SELECT ").append(time).append(",").append(primary).append(" FROM ").append(table).append(" WHERE ").append(primary).append("=#{p.before} AND merchant_id=#{p.m} AND environment=#{p.e})");
        }
        sql.append(" ORDER BY ").append(time).append(" DESC,").append(primary).append(" DESC LIMIT #{p.limit}");
        var rows = db.list(sql.toString(), p);
        int limit = CoreSupport.limit(b);
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        for (var row : rows) {
            row.remove("request_fingerprint");
            row.remove("worker_id");
            row.remove("lease_until");
            row.remove("id");
        }
        return map("items", rows, "has_more", more, "next_cursor", rows.isEmpty() ? null : rows.get(rows.size() - 1).get(primary));
    }

    private Map<String, Object> events(Tenant t, Map<String, Object> b) {
        long after;
        try {
            after = Long.parseLong(Objects.toString(b.getOrDefault("after_sequence", 0)));
            if (after < 0) throw new NumberFormatException();
        } catch (RuntimeException ex) {
            throw new BusinessException("INVALID_ARGUMENT", "after_sequence must be a nonnegative integer");
        }
        int limit = CoreSupport.limit(b);
        var p = support.tenant(t);
        p.putAll(map("after", after, "limit", limit + 1));
        var rows = db.list("SELECT * FROM events WHERE merchant_id=#{p.m} AND environment=#{p.e} AND event_sequence>#{p.after} ORDER BY event_sequence LIMIT #{p.limit}", p);
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        return map("items", rows, "has_more", more, "next_sequence", rows.isEmpty() ? after : rows.get(rows.size() - 1).get("event_sequence"));
    }

}
