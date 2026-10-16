package org.token.repository.compute;

import org.token.model.query.MemberScope;
import org.token.repository.support.SqlParameters;
import java.util.*;

/** 财务提醒只读取已落库状态，渠道开关与授权范围由服务实时传入。 */
public final class ComputeFinanceAttentionQueries {
    private ComputeFinanceAttentionQueries() { }
    public static String snapshot(Map<String,Object> p) {
        boolean withdrawal=(boolean)p.get("withdrawal"),gateway=(boolean)p.get("gateway");
        MemberScope scope=(MemberScope)p.get("scope");
        var queries=new ArrayList<String>();var args=new ArrayList<Object>();
        String table=withdrawal?"compute_withdrawal":"compute_deposit";
        queries.add("SELECT f.order_no reference,'PENDING' attention_state,'MANUAL' source,'0' version,'' reason,f.created_at changed_at FROM "
                +table+" f WHERE f.status='PENDING'"+(withdrawal?" AND f.attention_ignored=0":" AND COALESCE(f.record_source,'')<>'MCAG'")
                +MemberScopeQuery.memberFilter("f.member_id",scope,args));
        if(gateway) {
            if(withdrawal) {
                queries.add(remote("SELECT p.merchant_order_id reference,CASE WHEN p.remote_status='UNKNOWN' THEN 'UNKNOWN' WHEN p.remote_status IN ('FAILED','REJECTED') THEN 'FAILED' ELSE 'REVIEW' END attention_state,'PAYOUT' source,CAST(p.resource_version AS CHAR) version,p.reason,p.updated_at changed_at FROM compute_mcag_payout p JOIN compute_withdrawal f ON f.id=p.withdrawal_id WHERE f.attention_ignored=0 AND (p.state='REVIEW' OR p.remote_status='UNKNOWN')","p","f.member_id",args,p));
            } else {
                queries.add(remote("SELECT d.external_order_id reference,'REVIEW' attention_state,'DEPOSIT_ORDER' source,CAST(d.resource_version AS CHAR) version,d.reason,d.updated_at changed_at FROM compute_mcag_deposit_order d WHERE d.state='REVIEW'","d","d.member_id",args,p));
                queries.add(remote("SELECT d.batch_id reference,CASE WHEN d.sweep_status='FAILED' THEN 'FAILED' ELSE 'REVIEW' END attention_state,'COLLECTION' source,CAST(d.resource_version AS CHAR) version,d.reason,d.updated_at changed_at FROM compute_mcag_collection_batch d WHERE (d.state='REVIEW' OR d.sweep_status IN ('FAILED','REVIEW'))","d","d.member_id",args,p));
            }
        }
        return SqlParameters.bind("SELECT * FROM ("+String.join(" UNION ALL ",queries)+") attention ORDER BY changed_at DESC,source,reference LIMIT 501",args,p);
    }
    private static String remote(String sql,String alias,String memberColumn,List<Object>args,Map<String,Object>p) {
        args.add(p.get("merchantId"));args.add(p.get("environment"));
        return sql+" AND "+alias+".merchant_id=? AND "+alias+".environment=?"+MemberScopeQuery.memberFilter(memberColumn,(MemberScope)p.get("scope"),args);
    }
}
