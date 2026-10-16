package com.mcag.core;

import com.mcag.common.Amounts;
import com.mcag.common.BusinessException;
import com.mcag.common.Ids;
import com.mcag.domain.Tenant;
import com.mcag.infrastructure.Db;
import com.mcag.infrastructure.ListOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static com.mcag.common.Values.*;

/** A credit belongs to one transfer. Sweeps independently consume confirmed receipts. */
@Service
public class DepositOrderService {
    /** 商户查单仅返回核验和展示需要的字段，内部事件仍保留原有详情契约。 */
    private static final String MERCHANT_COLUMNS = """
            o.order_id,o.receipt_id,o.merchant_id,o.environment,o.network_id,o.asset_id,
            o.address,o.customer_id,o.purpose,o.amount,o.credit_threshold,o.policy_version,
            o.tx_hash,o.transfer_locator,o.block_height,o.block_hash,o.status,o.review_reason,
            o.resource_version,o.confirmed_at,o.credited_at,o.created_at,o.updated_at,
            a.symbol,a.decimals,r.address_id,r.sender_address,r.transfer_event_id,r.batch_id,
            r.accounting_model,r.current_confirmations,r.required_confirmations,r.chain_status,
            r.finality_satisfied,r.risk_status,r.credit_decision
            """;
    private final Db db;
    private final EventService events;
    private final ServiceFeeAccountService points;
    public DepositOrderService(Db db,EventService events,ServiceFeeAccountService points) {
        this.db=db;this.events=events;this.points=points;
    }

    public static boolean eligible(BigDecimal amount,BigDecimal threshold) {
        return amount!=null&&amount.signum()>0&&threshold!=null&&amount.compareTo(threshold)>=0;
    }

    @Transactional
    public Map<String,Object> credit(String receiptId) {
        var p=map("receipt",receiptId);
        var r=db.one("SELECT * FROM collection_receipts WHERE receipt_id=#{p.receipt} FOR UPDATE",p);
        if(r==null||!"SINGLE_RECEIPT".equals(r.get("accounting_model")))return null;
        var old=db.one("SELECT * FROM deposit_orders WHERE receipt_id=#{p.receipt} AND environment='LIVE'",p);
        if(old!=null)return old; // A reviewed/reorg credit is never paid a second time.
        if(!"CONFIRMED".equals(r.get("chain_status"))||!Boolean.TRUE.equals(r.get("finality_satisfied"))||!"CLEAR".equals(r.get("risk_status")))return null;
        if(!eligible(Amounts.value(r.get("amount")),Amounts.value(r.get("credit_threshold_snapshot")))) {
            db.update("UPDATE collection_receipts SET credit_decision='BELOW_THRESHOLD',updated_at=now() WHERE receipt_id=#{p.receipt} AND credit_decision='PENDING'",p);
            return null;
        }
        Tenant t=CollectionBatchService.tenant(r);
        boolean fee="SERVICE_FEE".equals(r.get("purpose"));
        if(fee&&!Boolean.TRUE.equals(r.get("service_fee_credited"))) {
            if(!points.creditTopup(t,receiptId,r.get("asset_id").toString(),Amounts.value(r.get("amount"))))return null;
            db.update("UPDATE collection_receipts SET service_fee_credited=true WHERE receipt_id=#{p.receipt} AND environment='LIVE'",p);
        }
        p.putAll(map("id",Ids.next("deposit"),"m",r.get("merchant_id"),"n",r.get("network_id"),"a",r.get("asset_id"),"w",r.get("wallet_id"),"address",r.get("address"),"customer",r.get("customer_id"),"purpose",r.get("purpose"),"amount",r.get("amount"),"threshold",r.get("credit_threshold_snapshot"),"policy",r.get("credit_policy_version"),"hash",r.get("tx_hash"),"locator",r.get("transfer_locator"),"height",r.get("block_height"),"blockhash",r.get("block_hash"),"confirmed",r.get("confirmed_at")));
        db.update("INSERT INTO deposit_orders(order_id,receipt_id,merchant_id,network_id,asset_id,wallet_id,address,customer_id,purpose,amount,credit_threshold,policy_version,tx_hash,transfer_locator,block_height,block_hash,confirmed_at) VALUES(#{p.id},#{p.receipt},#{p.m},#{p.n},#{p.a},#{p.w},#{p.address},#{p.customer},#{p.purpose},#{p.amount},#{p.threshold},#{p.policy},#{p.hash},#{p.locator},#{p.height},#{p.blockhash},#{p.confirmed})",p);
        db.update("UPDATE collection_receipts SET credit_decision='CREDITED',updated_at=now() WHERE receipt_id=#{p.receipt} AND environment='LIVE'",p);
        var result=detail(t,p.get("id").toString());
        if(!fee)events.publish(t,"deposit.credited","DEPOSIT_ORDER",p.get("id").toString(),1,result);
        return result;
    }

    @Transactional
    public void review(String receipt,String reason) {
        var p=map("receipt",receipt,"reason",reason);
        var order=db.one("UPDATE deposit_orders SET status='REVIEW',review_reason=#{p.reason},resource_version=resource_version+1,updated_at=now() WHERE receipt_id=#{p.receipt} AND status<>'REVIEW' RETURNING *",p);
        if(order!=null)events.publish(new Tenant(order.get("merchant_id").toString(),"LIVE"),"deposit.review","DEPOSIT_ORDER",order.get("order_id").toString(),((Number)order.get("resource_version")).longValue(),order);
    }

    public Map<String,Object> detail(Tenant tenant,String id) {
        var row=db.one("SELECT o.*,r.current_confirmations,r.required_confirmations,r.chain_status,r.credit_decision FROM deposit_orders o JOIN collection_receipts r USING(receipt_id) WHERE o.environment='LIVE' AND o.order_id=#{p.id} AND o.merchant_id=#{p.m}",map("id",id,"m",tenant.merchantId()));
        if(row==null)throw new BusinessException("NOT_FOUND","充值订单不存在");
        return row;
    }
    @Transactional
    public Map<String,Object> resolveReview(String id,Map<String,Object> body,String actor) {
        String reason=required(body,"reason");if(reason.length()>1000)throw new BusinessException("INVALID_ARGUMENT","核查说明过长");
        var found=db.one("SELECT receipt_id FROM deposit_orders WHERE order_id=#{p.id}",map("id",id));
        if(found==null)throw new BusinessException("NOT_FOUND","充值订单不存在");
        var receipt=db.one("SELECT * FROM collection_receipts WHERE receipt_id=#{p.r} FOR UPDATE",map("r",found.get("receipt_id")));
        var order=db.one("SELECT * FROM deposit_orders WHERE order_id=#{p.id} FOR UPDATE",map("id",id));
        long version;
        try{version=new BigDecimal(Objects.toString(body.get("resource_version"),"")).longValueExact();}catch(Exception ex){throw new BusinessException("INVALID_ARGUMENT","订单版本无效");}
        if(((Number)order.get("resource_version")).longValue()!=version)throw new BusinessException("VERSION_CONFLICT","订单已变化，请刷新");
        if(!"REVIEW".equals(order.get("status")))throw new BusinessException("INVALID_STATE","只有待核查订单可以恢复");
        if(receipt==null||!"CONFIRMED".equals(receipt.get("chain_status"))||!Boolean.TRUE.equals(receipt.get("finality_satisfied"))||!"CLEAR".equals(receipt.get("risk_status"))||Amounts.value(order.get("amount")).compareTo(Amounts.value(receipt.get("amount")))!=0)
            throw new BusinessException("DEPOSIT_REVIEW_REQUIRED","原收款尚未重新确认，不能恢复订单");
        db.update("UPDATE deposit_orders SET status='CONFIRMED',review_reason=NULL,resource_version=resource_version+1,updated_at=now() WHERE order_id=#{p.id}",map("id",id));
        db.update("INSERT INTO audit_logs(audit_id,actor,action,resource_type,resource_id,details) VALUES(#{p.audit},#{p.actor},'DEPOSIT_REVIEW_RESOLVE','DEPOSIT_ORDER',#{p.id},jsonb_build_object('reason',CAST(#{p.reason} AS text)))",map("audit",Ids.next("audit"),"actor",actor,"id",id,"reason",reason));
        var t=new Tenant(order.get("merchant_id").toString(),"LIVE");var result=detail(t,id);
        events.publish(t,"deposit.reconfirmed","DEPOSIT_ORDER",id,version+1,result);
        return result;
    }
    public Map<String,Object> query(Tenant tenant,Map<String,Object> body) {
        var p=new LinkedHashMap<>(body);p.put("merchant_id",tenant.merchantId());
        p.put("environment",tenant.environment());return read(p,false);
    }
    public Map<String,Object> adminQuery(Map<String,Object> body) {return read(body,true);}
    private Map<String,Object> read(Map<String,Object> body,boolean paged) {
        int page=paged?MerchantBillService.number(Objects.toString(body.get("page"),null),1,100000):1;
        int limit=paged?MerchantBillService.number(Objects.toString(body.get("page_size"),null),20,100):CoreSupport.limit(body);
        var p=map("limit",paged?limit:limit+1,"offset",(long)(page-1)*limit);
        var sql=new StringBuilder(" FROM deposit_orders o JOIN assets a USING(asset_id) JOIN collection_receipts r USING(receipt_id) WHERE o.environment='LIVE'");
        if(!paged) {p.put("environment",body.get("environment"));sql.append(" AND o.environment=#{p.environment}");}
        for(String key:List.of("merchant_id","network_id","asset_id","customer_id","purpose","status","order_id"))if(body.get(key)!=null&&!body.get(key).toString().isBlank()) {p.put(key,body.get(key).toString());sql.append(" AND o.").append(key).append("=#{p.").append(key).append("}");}
        if(body.get("address")!=null&&!body.get("address").toString().isBlank()) {p.put("address",body.get("address").toString());sql.append(" AND (o.address=#{p.address} OR (EXISTS(SELECT 1 FROM networks n WHERE n.network_id=o.network_id AND n.family='EVM') AND lower(o.address)=lower(#{p.address})))");}
        if(body.get("tx_hash")!=null&&!body.get("tx_hash").toString().isBlank()) {p.put("hash",body.get("tx_hash").toString());sql.append(" AND o.tx_hash=#{p.hash}");}
        var times=new HashMap<String,String>();for(String key:List.of("start_time","end_time"))if(body.get(key)!=null)times.put(key,body.get(key).toString());
        MerchantBillService.timeFilter(sql,p,"o.credited_at",times);
        if(!paged&&body.get("before_id")!=null){p.put("before",body.get("before_id").toString());sql.append(" AND (o.credited_at,o.order_id)<(SELECT credited_at,order_id FROM deposit_orders WHERE order_id=#{p.before} AND merchant_id=#{p.merchant_id} AND environment='LIVE')");}
        if(paged) {
            var allowed=ListOrder.columns("o","order_id,merchant_id,network_id,asset_id,customer_id,purpose,status,address,amount,tx_hash,block_height,confirmed_at,credited_at,created_at");
            allowed.put("symbol","a.symbol");allowed.put("current_confirmations","r.current_confirmations");allowed.put("required_confirmations","r.required_confirmations");
            String order=ListOrder.clause(body,allowed,"o.credited_at DESC,o.order_id DESC");
            return map("items",db.list("SELECT o.*,a.symbol,r.current_confirmations,r.required_confirmations,r.chain_status"+sql+order+" LIMIT #{p.limit} OFFSET #{p.offset}",p),"total",db.one("SELECT count(*) total"+sql,p).get("total"),"page",page,"page_size",limit);
        }
        sql.append(" ORDER BY o.credited_at DESC,o.order_id DESC LIMIT #{p.limit}");
        var rows=db.list("SELECT "+MERCHANT_COLUMNS+sql,p);return map("items",rows.subList(0,Math.min(rows.size(),limit)),"has_more",rows.size()>limit);
    }
}
