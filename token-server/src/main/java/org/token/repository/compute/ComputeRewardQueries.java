package org.token.repository.compute;

import java.util.Map;

/** 当前有效客户资格按全历史判断，不能把活动切换点用于伪造首充。 */
public final class ComputeRewardQueries {
    private ComputeRewardQueries() { }
    public static String qualifiedCount(Map<String,Object> p) {
        String sql="SELECT m.id FROM compute_member m JOIN compute_deposit d ON d.member_id=m.id AND d.status='APPROVED' WHERE m.inviter_id=#{owner} AND CASE WHEN d.accounting_version=2 THEN 'USDT' ELSE d.currency END=#{currency}";
        if(p.get("customer")!=null)sql+=" AND m.id=#{customer}";
        if(Boolean.TRUE.equals(p.get("first")))sql+=" AND NOT EXISTS(SELECT 1 FROM compute_deposit f WHERE f.member_id=d.member_id AND f.status='APPROVED' AND (COALESCE(f.reviewed_at,f.created_at)<COALESCE(d.reviewed_at,d.created_at) OR (COALESCE(f.reviewed_at,f.created_at)=COALESCE(d.reviewed_at,d.created_at) AND f.id<d.id)))";
        return "SELECT COUNT(*) FROM ("+sql+" GROUP BY m.id HAVING SUM(CASE WHEN d.accounting_version=2 THEN d.accounting_amount ELSE d.amount END)>=#{threshold}) qualified_customers";
    }
}
