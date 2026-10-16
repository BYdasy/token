package org.token.repository.compute;

import org.token.model.query.ActivityDepositQuery;
import org.token.repository.support.SqlParameters;
import java.util.*;

/** 活动充值候选查询；只有成功来源可入选，旧已发凭据不在本类修改。 */
public final class ComputeActivityQualificationQueries {
    private ComputeActivityQualificationQueries() { }
    private static final String AT="COALESCE(d.reviewed_at,d.created_at)";
    private static final String AMOUNT="CASE WHEN d.accounting_version=2 THEN d.accounting_amount ELSE d.amount END";
    private static final String UNIT="CASE WHEN d.accounting_version=2 OR d.currency IN('USD','USDT','USDC') THEN 'USDT' ELSE d.currency END";
    private static String first(){return " AND NOT EXISTS (SELECT 1 FROM compute_deposit earlier WHERE earlier.member_id=d.member_id AND earlier.status='APPROVED' AND (COALESCE(earlier.reviewed_at,earlier.created_at)<"+AT+" OR (COALESCE(earlier.reviewed_at,earlier.created_at)="+AT+" AND earlier.id<d.id)))";}
    private static ActivityDepositQuery query(Map<String,Object> p){return (ActivityDepositQuery)p.get("query");}
    public static String earned(Map<String,Object> p){
        var q=query(p);List<Object> args=new ArrayList<>(List.of(q.memberId(),q.currency(),q.start(),q.end()));
        String base=" FROM compute_deposit d JOIN compute_member m ON m.id=d.member_id WHERE m.inviter_id=? AND "+UNIT+"=? AND d.status='APPROVED' AND "+AT+">=? AND "+AT+"<?";
        base+=TestMemberSourceQuery.filter("m.is_test",AT,q.testParticipationSince(),args);
        String sql;
        if(Boolean.TRUE.equals(p.get("first"))){args.add(p.get("threshold"));sql="SELECT COUNT(*)"+base+" AND ("+AMOUNT+")>=?"+first();}
        else {args.add(0,p.get("threshold"));sql="SELECT COALESCE(SUM(FLOOR(q.amount/?)),0) FROM (SELECT d.member_id,SUM("+AMOUNT+") amount"+base+" GROUP BY d.member_id) q";}
        return SqlParameters.bind(sql,args,p);
    }
    public static String wheelTiers(Map<String,Object> p){
        var q=query(p);List<Object> args=new ArrayList<>(List.of(q.memberId(),q.memberId(),q.start(),q.end()));
        String sql="SELECT d.member_id,m.inviter_id,SUM("+AMOUNT+") amount FROM compute_deposit d JOIN compute_member m ON m.id=d.member_id WHERE (m.id=? OR m.inviter_id=?) AND d.status='APPROVED' AND "+UNIT+"='USDT' AND "+AT+">=? AND "+AT+"<?";
        sql+=TestMemberSourceQuery.filter("m.is_test",AT,q.testParticipationSince(),args);
        if(Boolean.TRUE.equals(p.get("first")))sql+=first();
        return SqlParameters.bind(sql+" GROUP BY d.member_id,m.inviter_id",args,p);
    }
    public static String chestTiers(Map<String,Object> p){
        var q=query(p);boolean single=Boolean.TRUE.equals(p.get("single"));List<Object> args=new ArrayList<>(List.of(q.memberId(),q.start(),q.end()));
        String sql="SELECT "+(single?"d.id":"0")+" source_deposit_id,"+(single?AMOUNT:"SUM("+AMOUNT+")")+" amount FROM compute_deposit d JOIN compute_member m ON m.id=d.member_id WHERE d.member_id=? AND d.status='APPROVED' AND "+UNIT+"='USDT' AND "+AT+">=? AND "+AT+"<?";
        sql+=TestMemberSourceQuery.filter("m.is_test",AT,q.testParticipationSince(),args);
        if(Boolean.TRUE.equals(p.get("first")))sql+=first();
        if(!single)sql+=" GROUP BY d.member_id";
        return SqlParameters.bind(sql,args,p);
    }
    public static String priorGrants(Map<String,Object> p){
        String sources=Boolean.TRUE.equals(p.get("chest"))?"source_member_id=#{memberId}":"(source_member_id=#{memberId} OR source_member_id IN(SELECT id FROM compute_member WHERE inviter_id=#{memberId}))";
        return "SELECT * FROM compute_activity_attempt_grant WHERE activity_id=#{activityId} AND round_no=#{round} AND "+sources;
    }
    public static String invitedMembers(Map<String,Object> p){
        var q=query(p);List<Object> args=new ArrayList<>(List.of(q.memberId(),p.get("minimum"),q.start(),q.end()));
        String source=TestMemberSourceQuery.filter("m.is_test",AT,q.testParticipationSince(),args);args.add(p.get("limit"));
        return SqlParameters.bind("SELECT d.id deposit_id,d.member_id,"+AMOUNT+" accounting_amount FROM compute_deposit d JOIN compute_member m ON m.id=d.member_id"
                +" WHERE m.inviter_id=? AND d.status='APPROVED' AND "+UNIT+"='USDT' AND "+AMOUNT+">=? AND "+AT+">=? AND "+AT+"<?"+source+first()+" ORDER BY d.member_id,d.id LIMIT ?",args,p);
    }
}
