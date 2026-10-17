package org.token.repository.system;

import org.token.model.query.MemberScope;
import org.token.repository.compute.MemberScopeQuery;
import org.token.repository.support.SqlParameters;
import java.util.*;

/** 通知查询只读业务事实，不在此改变订单、审核或余额。 */
public final class NotificationAutomationQueries {
    private NotificationAutomationQueries() { }
    public static String collectFinanceEvents(Map<String,Object> p) {
        boolean withdrawal=(boolean)p.get("withdrawal");
        String table=withdrawal?"compute_withdrawal":"compute_deposit",resource=withdrawal?"WITHDRAWAL":"DEPOSIT";
        return "INSERT INTO notification_finance_event(resource,source_id,created_at) SELECT '"+resource+"',f.id,#{now} FROM "+table+" f WHERE f.created_at>=#{since} AND NOT EXISTS (SELECT 1 FROM notification_finance_event e WHERE e.resource='"+resource+"' AND e.source_id=f.id) ORDER BY f.id LIMIT 200";
    }
    public static String financeEvents(Map<String,Object> p) {
        boolean withdrawal=(boolean)p.get("withdrawal"),maximum=(boolean)p.get("maximum");
        String table=withdrawal?"compute_withdrawal":"compute_deposit",resource=withdrawal?"WITHDRAWAL":"DEPOSIT";
        List<Object> args=new ArrayList<>();
        String sql=(maximum?"SELECT COALESCE(MAX(e.id),0) cursor":"SELECT e.id,e.source_id,e.created_at")
                +" FROM notification_finance_event e JOIN "+table+" f ON f.id=e.source_id WHERE e.resource='"+resource+"'"
                +MemberScopeQuery.memberFilter("f.member_id",(MemberScope)p.get("scope"),args);
        if(!maximum)sql+=" AND e.id>#{after} AND f.created_at>=#{since} ORDER BY e.id LIMIT #{limit}";
        return SqlParameters.bind(sql,args,p);
    }
    public static String candidates(Map<String,Object> p) {
        String event=(String)p.get("event");
        boolean deposit="largeDeposit".equals(event),overdue="withdrawalOverdue".equals(event);
        String table=deposit?"compute_deposit":"compute_withdrawal",kind=deposit?"DEPOSIT":"WITHDRAWAL";
        String amount="CASE WHEN f.accounting_version=2 AND f.accounting_currency='USD' THEN f.accounting_amount WHEN COALESCE(f.accounting_version,1)<>2 THEN s.usd_amount ELSE NULL END";
        String condition=deposit?"f.status='APPROVED' AND COALESCE(f.reviewed_at,f.created_at)>=#{since}":"f.created_at>=#{since}";
        if(overdue)condition+=" AND f.status='PENDING' AND f.attention_ignored=0 AND f.created_at<=#{before}";
        else condition+=" AND "+amount+">=#{threshold}";
        // CAST(number AS CHAR) carries the connection's implicit collation and can
        // conflict with source_key. CONCAT with a literal remains coercible to
        // the indexed column's collation, while preserving the exact BIGINT text.
        return "SELECT f.id,f.member_id,f.order_no,f.created_at,"+amount+" amount,m.username FROM "+table+" f JOIN compute_member m ON m.id=f.member_id LEFT JOIN compute_finance_snapshot s ON s.business_type='"+kind+"' AND s.business_id=f.id WHERE "+condition
                +(p.containsKey("source")?" AND f.id=#{source}":" AND NOT EXISTS(SELECT 1 FROM notification_event_delivery d WHERE d.event_code=#{event} AND d.source_key=CONCAT('',f.id) AND d.target_key='EVENT')")+" ORDER BY f.id LIMIT 100";
    }
    public static String eligible(Map<String,Object> p) {
        return "SELECT COUNT(*) FROM ("+candidates(p)+") eligible_event";
    }
}
