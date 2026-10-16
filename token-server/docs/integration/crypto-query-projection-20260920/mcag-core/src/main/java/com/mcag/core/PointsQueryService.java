package com.mcag.core;

import com.mcag.common.BusinessException;
import com.mcag.domain.Tenant;
import com.mcag.infrastructure.Db;
import com.mcag.infrastructure.ListOrder;
import org.springframework.stereotype.Service;

import java.util.*;
import static com.mcag.common.Values.map;

/** Read models for points top-ups. Reading never creates an address or token an account. */
@Service
public class PointsQueryService {
    private final Db db;
    private final WalletBalanceService balances;
    @org.springframework.beans.factory.annotation.Autowired private EvmWalletGroupService groups;
    private static final String CREDIT_STATUS = "CASE WHEN r.risk_status<>'CLEAR' OR r.chain_status='ORPHANED' THEN 'REVIEW' WHEN r.service_fee_credited THEN 'CREDITED' WHEN r.credit_decision='BELOW_THRESHOLD' THEN 'BELOW_THRESHOLD' WHEN r.chain_status='CONFIRMED' AND r.finality_satisfied THEN 'WAITING_PRICE' ELSE 'WAITING_CONFIRMATION' END";
    private static final String FROM = " FROM collection_receipts r LEFT JOIN service_fee_entries e ON e.merchant_id=r.merchant_id AND e.environment=r.environment AND e.entry_type='TOPUP' AND e.resource_id=r.receipt_id LEFT JOIN service_fee_price_snapshots p ON p.snapshot_id=e.price_snapshot_id LEFT JOIN collection_batches b ON b.batch_id=r.batch_id LEFT JOIN custody_operations o ON o.operation_id=b.operation_id JOIN assets a ON a.asset_id=r.asset_id";
    private static final String COLUMNS = "r.receipt_id,r.merchant_id,r.network_id,r.asset_id,a.symbol,r.address,r.sender_address,r.amount,r.tx_hash,r.transfer_locator,r.chain_status,r.risk_status,r.block_height,r.detected_at,r.confirmed_at,r.current_confirmations,r.required_confirmations,r.finality_satisfied,r.service_fee_credited,r.credit_decision,r.credit_threshold_snapshot,(SELECT d.order_id FROM deposit_orders d WHERE d.receipt_id=r.receipt_id) AS deposit_order_id,r.batch_id,e.entry_id,e.available_delta AS credited_points,e.created_at AS credited_at,e.price_snapshot_id,p.usdt_price,p.source AS price_source,p.observed_at AS price_observed_at,"+CREDIT_STATUS+" AS credit_status,COALESCE(b.sweep_status,'WAITING_BATCH') AS sweep_status,b.destination_address,b.operation_id,o.tx_hash AS sweep_tx_hash,o.actual_fee_native,o.reason_code";

    public PointsQueryService(Db db, WalletBalanceService balances) { this.db=db;this.balances=balances; }

    public Map<String,Object> topups(Map<String,String> filters) {
        int page=MerchantBillService.number(filters.get("page"),1,100000),size=MerchantBillService.number(filters.get("page_size"),20,100);
        var p=map("limit",size,"offset",(long)(page-1)*size);
        var where=new StringBuilder(" WHERE r.environment='LIVE' AND r.purpose='SERVICE_FEE'");
        for(String key:List.of("merchant_id","network_id","asset_id","address","tx_hash","batch_id")) MerchantBillService.filter(where,p,"r."+key,key,filters.get(key));
        MerchantBillService.filter(where,p,"("+CREDIT_STATUS+")","status",filters.get("status"));
        MerchantBillService.timeFilter(where,p,"r.detected_at",filters);
        var sortable=ListOrder.columns("r","receipt_id,merchant_id,network_id,asset_id,address,sender_address,amount,tx_hash,transfer_locator,chain_status,risk_status,block_height,detected_at,confirmed_at,current_confirmations,required_confirmations,finality_satisfied,service_fee_credited,batch_id");
        sortable.putAll(ListOrder.columns("e","entry_id,price_snapshot_id"));
        sortable.putAll(ListOrder.columns("b","destination_address,operation_id"));
        sortable.putAll(ListOrder.columns("o","actual_fee_native,reason_code"));
        sortable.put("symbol","a.symbol");sortable.put("credited_points","e.available_delta");sortable.put("credited_at","e.created_at");
        sortable.put("usdt_price","p.usdt_price");sortable.put("price_source","p.source");sortable.put("price_observed_at","p.observed_at");
        sortable.put("credit_status",CREDIT_STATUS);sortable.put("sweep_status","COALESCE(b.sweep_status,'WAITING_BATCH')");sortable.put("sweep_tx_hash","o.tx_hash");
        String order=ListOrder.clause(filters,sortable,"r.detected_at DESC,r.receipt_id DESC");
        return map("items",db.list("SELECT "+COLUMNS+FROM+where+order+" LIMIT #{p.limit} OFFSET #{p.offset}",p),
                "total",db.one("SELECT count(*) total"+FROM+where,p).get("total"),"page",page,"page_size",size);
    }

    public Map<String,Object> topup(String receiptId) {
        var p=map("id",receiptId);
        var row=db.one("SELECT "+COLUMNS+FROM+" WHERE r.receipt_id=#{p.id} AND r.environment='LIVE' AND r.purpose='SERVICE_FEE'",p);
        if(row==null) throw new BusinessException("NOT_FOUND","积分充值记录不存在");
        var entry=db.one("SELECT e.*,p.usdt_price,p.source AS price_source,p.observed_at AS price_observed_at FROM service_fee_entries e LEFT JOIN service_fee_price_snapshots p ON p.snapshot_id=e.price_snapshot_id WHERE e.environment='LIVE' AND e.entry_type='TOPUP' AND e.resource_id=#{p.id} AND e.merchant_id=#{p.m}",map("id",receiptId,"m",row.get("merchant_id")));
        Map<String,Object> batch=null;
        List<Map<String,Object>> operations=List.of();
        if(row.get("batch_id")!=null) {
            var bp=map("id",row.get("batch_id"),"m",row.get("merchant_id"));
            batch=db.one("SELECT batch_id,merchant_id,network_id,asset_id,amount,status,sweep_status,destination_address,reason_code,created_at,updated_at FROM collection_batches WHERE batch_id=#{p.id} AND merchant_id=#{p.m} AND environment='LIVE'",bp);
            if(batch!=null) batch.put("items",db.list("SELECT receipt_id,amount,tx_hash,transfer_locator,chain_status,service_fee_credited FROM collection_receipts WHERE batch_id=#{p.id} AND merchant_id=#{p.m} AND purpose='SERVICE_FEE' ORDER BY block_height,receipt_id",bp));
            operations=db.list("SELECT operation_id,operation_type,asset_id,amount,destination_address,status,tx_hash,actual_fee_native,reason_code,created_at,updated_at FROM custody_operations WHERE batch_id=#{p.id} AND merchant_id=#{p.m} AND environment='LIVE' ORDER BY created_at,operation_id",bp);
        }
        return map("receipt",row,"points_entry",entry,"batch",batch,"operations",operations);
    }

    public Map<String,Object> addresses(Tenant tenant,String network,boolean internal) {
        var p=map("m",tenant.merchantId(),"e",tenant.environment(),"n",network);
        var rows=db.list("SELECT b.address_id,b.merchant_id,b.environment,b.network_id,b.purpose,b.address,b.wallet_id,b.status,b.monitoring_ready,b.created_at,(SELECT w.evm_group_id FROM wallets w WHERE w.wallet_id=b.wallet_id) AS group_id FROM address_bindings b WHERE merchant_id=#{p.m} AND environment=#{p.e} AND purpose='SERVICE_FEE' AND (CAST(#{p.n} AS varchar) IS NULL OR network_id=#{p.n}) ORDER BY network_id",p);
        if(internal && groups!=null) {
            var grouped=new LinkedHashMap<String,Map<String,Object>>();
            for(var row:rows) {
                String key=Objects.toString(row.get("group_id"),row.get("wallet_id").toString());
                if(grouped.containsKey(key))continue;
                if(row.get("group_id")!=null) row.putAll(groups.byWallet(row.get("wallet_id").toString()));
                else {var snapshot=balances.wallet(row.get("wallet_id").toString(),false);row.put("balance_items",snapshot.get("items"));row.put("balance_checked_at",snapshot.get("checked_at"));}
                grouped.put(key,row);
            }
            return map("items",new ArrayList<>(grouped.values()));
        }
        for(var row:rows) {
            var result=balances.wallet(row.get("wallet_id").toString(),false);
            row.put("balance_items",result.get("items"));row.put("balance_checked_at",result.get("checked_at"));row.put("balance_scope",result.get("balance_scope"));
            if(!internal) row.remove("wallet_id");
        }
        return map("items",rows);
    }

    public Map<String,Object> balance(Tenant tenant,String addressId) {
        var row=db.one("SELECT address_id,merchant_id,environment,network_id,purpose,address,wallet_id,status,monitoring_ready FROM address_bindings WHERE merchant_id=#{p.m} AND environment=#{p.e} AND address_id=#{p.id} AND purpose='SERVICE_FEE'",map("m",tenant.merchantId(),"e",tenant.environment(),"id",addressId));
        if(row==null) throw new BusinessException("NOT_FOUND","积分充值地址不存在");
        var result=new LinkedHashMap<>(balances.wallet(row.get("wallet_id").toString(),false));
        result.putAll(row);result.remove("wallet_id");return result;
    }
}
