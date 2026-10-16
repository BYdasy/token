package org.token.repository.compute;

import org.token.model.query.MemberScope;
import org.token.model.query.ReportMemberFilter;
import org.token.model.query.ReportBucket;
import org.token.repository.support.SqlParameters;
import java.time.LocalDateTime;
import java.util.*;

/** 运营与首页报表注解 SQL Provider；查询名称和列名均来自固定集合。 */
public final class ComputeReportQueries {
    private ComputeReportQueries() { }

    public static String visibleUsername(Map<String,Object> parameters) {
        List<Object> args=new ArrayList<>(List.of(parameters.get("username")));
        return SqlParameters.bind("SELECT m.id FROM compute_member m WHERE m.username=?"
                +MemberScopeQuery.memberFilter("m.id",(MemberScope)parameters.get("scope"),args),args,parameters);
    }

    public static String period(Map<String,Object> parameters) {
        String resource=(String)parameters.get("resource");
        @SuppressWarnings("unchecked") List<ReportBucket> buckets=(List<ReportBucket>)parameters.get("buckets");
        String table,time,base;Map<String,String> measures=new LinkedHashMap<>();
        switch(resource) {
            case "members"->{table="compute_member m";time="m.created_at";base="";measures.put("newMembers","1");}
            case "deposits"->{table=financeTable("DEPOSIT");time="f.reviewed_at";base=" AND f.status='APPROVED'";measures.put("depositAmount","CASE WHEN m.is_test=0 THEN COALESCE(s.usd_amount,0) ELSE 0 END");measures.put("depositCount","1");measures.put("unvaluedCount","CASE WHEN m.is_test=0 AND s.usd_rate IS NULL THEN 1 ELSE 0 END");}
            case "withdrawals"->{table=financeTable("WITHDRAWAL");time="f.reviewed_at";base=" AND f.status='APPROVED'";measures.put("withdrawalAmount","CASE WHEN m.is_test=0 THEN COALESCE(s.usd_net_amount,0) ELSE 0 END");measures.put("withdrawalFee","CASE WHEN m.is_test=0 THEN COALESCE(s.usd_fee,0) ELSE 0 END");measures.put("withdrawalGrossAmount","CASE WHEN m.is_test=0 THEN COALESCE(s.usd_amount,0) ELSE 0 END");measures.put("withdrawalCount","1");measures.put("unvaluedCount","CASE WHEN m.is_test=0 AND s.usd_rate IS NULL THEN 1 ELSE 0 END");}
            default->throw new IllegalArgumentException("Unsupported report resource");
        }
        List<String> columns=new ArrayList<>();List<Object> args=new ArrayList<>();
        for(int index=0;index<buckets.size();index++)for(var measure:measures.entrySet()) {
            columns.add("COALESCE(SUM(CASE WHEN "+time+">=? AND "+time+"<? THEN "+measure.getValue()+" ELSE 0 END),0) v_"+index+"_"+measure.getKey());
            args.add(buckets.get(index).from());args.add(buckets.get(index).to());
        }
        if("deposits".equals(resource))for(int i=0;i<buckets.size();i++)peopleColumns(columns,args,"v_"+i+"_",buckets.get(i).from(),buckets.get(i).to());
        String where=" WHERE 1=1"+base+memberFilter((ReportMemberFilter)parameters.get("filter"),args);
        where+=" AND "+time+">=? AND "+time+"<?";args.add(parameters.get("from"));args.add(parameters.get("to"));
        return SqlParameters.bind("SELECT "+String.join(",",columns)+" FROM "+table+where,args,parameters);
    }

    public static String people(Map<String,Object> parameters) {
        List<String> columns=new ArrayList<>();List<Object> args=new ArrayList<>();
        peopleColumns(columns,args,"",(LocalDateTime)parameters.get("from"),(LocalDateTime)parameters.get("to"));
        String where=" WHERE f.status='APPROVED'"+memberFilter((ReportMemberFilter)parameters.get("filter"),args)+" AND f.reviewed_at>=? AND f.reviewed_at<?";
        args.add(parameters.get("from"));args.add(parameters.get("to"));
        return SqlParameters.bind("SELECT "+String.join(",",columns)+" FROM compute_deposit f JOIN compute_member m ON m.id=f.member_id"+where,args,parameters);
    }

    private static void peopleColumns(List<String> columns,List<Object> args,String prefix,LocalDateTime from,LocalDateTime to) {
        for(String metric:List.of("depositMembers","firstDepositMembers")) {
            String firstCheck="firstDepositMembers".equals(metric)?" AND NOT EXISTS(SELECT 1 FROM compute_deposit earlier WHERE earlier.member_id=f.member_id AND earlier.status='APPROVED' AND (earlier.reviewed_at IS NULL OR earlier.reviewed_at<f.reviewed_at OR (earlier.reviewed_at=f.reviewed_at AND earlier.id<f.id)))":"";
            columns.add("COUNT(DISTINCT CASE WHEN f.reviewed_at>=? AND f.reviewed_at<?"+firstCheck+" THEN f.member_id END) "+prefix+metric);
            args.add(from);args.add(to);
        }
    }

    public static String homeMembers(Map<String,Object> p) { return homeCount(p,"SELECT COUNT(*) FROM compute_member m WHERE 1=1"); }
    public static String homeActivated(Map<String,Object> p) { return homeCount(p,"SELECT COUNT(*) FROM compute_member m WHERE m.level>1"); }
    public static String homeDepositors(Map<String,Object> p) { return homeCount(p,"SELECT COUNT(DISTINCT f.member_id) FROM compute_deposit f JOIN compute_member m ON m.id=f.member_id WHERE f.status='APPROVED'"); }
    private static String homeCount(Map<String,Object> p,String prefix) { List<Object>a=new ArrayList<>();return SqlParameters.bind(prefix+memberFilter((ReportMemberFilter)p.get("filter"),a),a,p); }

    public static String homeTotals(Map<String,Object> p) {
        String kind=kind(p);List<Object>args=new ArrayList<>();String amount="s.usd_amount";
        String sql="SELECT COALESCE(SUM("+amount+"),0) amount,COALESCE(SUM(s.usd_fee),0) fee,COALESCE(SUM(s.usd_net_amount),0) net_amount,COALESCE(SUM(CASE WHEN s.usd_rate IS NULL THEN 1 ELSE 0 END),0) missing FROM "+financeTable(kind)+" WHERE f.status='APPROVED' AND m.is_test=0"+memberFilter((ReportMemberFilter)p.get("filter"),args);
        return SqlParameters.bind(sql,args,p);
    }
    public static String homeBalances(Map<String,Object> p) {
        List<Object>args=new ArrayList<>();String sql="SELECT w.currency,SUM(w.available) available FROM compute_wallet w JOIN compute_member m ON m.id=w.member_id WHERE w.wallet_type='EARNINGS' AND m.is_test=0"+memberFilter((ReportMemberFilter)p.get("filter"),args)+" GROUP BY w.currency";
        return SqlParameters.bind(sql,args,p);
    }
    public static String detailCount(Map<String,Object> p) {
        List<Object>args=new ArrayList<>();return SqlParameters.bind("SELECT COUNT(*)"+detailFrom(p,args),args,p);
    }
    public static String details(Map<String,Object> p) {
        String kind=kind(p);List<Object>args=new ArrayList<>();String from=detailFrom(p,args);
        String fee="DEPOSIT".equals(kind)?"0":"f.fee",net="DEPOSIT".equals(kind)?"f.amount":"f.net_amount";
        args.add(p.get("size"));args.add(p.get("offset"));
        return SqlParameters.bind("SELECT f.order_no,m.username,f.currency,f.amount native_amount,"+fee+" native_fee,"+net+" native_net_amount,f.reviewed_at posted_at,s.usd_rate,s.usd_amount,s.usd_fee,s.usd_net_amount,s.rate_provider,s.rate_source_at,s.snapshot_source,s.captured_at"+from+" ORDER BY f.reviewed_at DESC,f.id DESC LIMIT ? OFFSET ?",args,p);
    }
    private static String detailFrom(Map<String,Object> p,List<Object>args) { return " FROM "+financeTable(kind(p))+" WHERE f.status='APPROVED' AND m.is_test=0"+memberFilter((ReportMemberFilter)p.get("filter"),args); }
    private static String kind(Map<String,Object> p) { String kind=(String)p.get("kind");if(!Set.of("DEPOSIT","WITHDRAWAL").contains(kind))throw new IllegalArgumentException("Unsupported finance kind");return kind; }
    private static String financeTable(String kind) {
        String table=switch(kind){case "DEPOSIT"->"compute_deposit";case "WITHDRAWAL"->"compute_withdrawal";default->throw new IllegalArgumentException("不支持的财务快照类型");};
        return table+" f JOIN compute_member m ON m.id=f.member_id LEFT JOIN compute_finance_snapshot s ON s.business_id=f.id AND s.business_type='"+kind+"'";
    }
    private static String memberFilter(ReportMemberFilter filter,List<Object>args) {
        String where=MemberScopeQuery.memberFilter("m.id",filter.scope(),args);
        if(filter.groupId()!=null) {
            if(filter.groupId()==0)where+=" AND COALESCE(m.group_id,0)=0";else{where+=" AND m.group_id=?";args.add(filter.groupId());}
        }
        if(filter.teamMemberId()!=null)where+=MemberScopeQuery.descendantCondition("m.id",List.of(filter.teamMemberId()),args);
        return where;
    }
}
