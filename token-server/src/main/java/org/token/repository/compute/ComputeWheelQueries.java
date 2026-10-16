package org.token.repository.compute;

import org.token.exception.BusinessException;
import org.token.model.query.MemberScope;
import org.token.repository.support.SqlParameters;
import java.time.*;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 转盘会员列表与活动统计的命名数据库查询。 */
public final class ComputeWheelQueries {
    private ComputeWheelQueries() { }
    @SuppressWarnings("unchecked") private static Map<String,String> query(Map<String,Object> p){return (Map<String,String>)p.get("query");}
    private static String members(Map<String,Object> p,boolean count){
        long activityId=number(p.get("activityId"));Map<String,String> query=query(p);
        List<Object> args=new ArrayList<>(List.of(activityId,number(p.get("round"))));
        String table=" FROM compute_member m LEFT JOIN compute_wheel_member w ON w.member_id=m.id AND w.activity_id=? AND w.round_no=?";
        String where=" WHERE 1=1"+MemberScopeQuery.memberFilter("m.id",(MemberScope)p.get("scope"),args);
        if(!text(query.get("keyword")).isEmpty()){where+=" AND m.username LIKE ?";args.add("%"+text(query.get("keyword"))+"%");}
        if(!text(query.get("memberId")).isEmpty()){where+=" AND m.id=?";args.add(number(query.get("memberId")));}
        if(!text(query.get("controlType")).isEmpty()){where+=" AND COALESCE(w.control_type,'UNIFIED')=?";args.add(query.get("controlType"));}
        if(count)return SqlParameters.bind("SELECT COUNT(*)"+table+where,args,p);
        int size=(int)Math.max(1,Math.min(100,number(query.getOrDefault("size","20"))));
        args.add(size);args.add((Math.max(1,number(query.getOrDefault("page","1")))-1)*size);
        return SqlParameters.bind("SELECT w.*,m.id listed_member_id,m.username"+table+where+" ORDER BY COALESCE(w.available_count,0) DESC,m.id DESC LIMIT ? OFFSET ?",args,p);
    }
    public static String membersCount(Map<String,Object> p){return members(p,true);}
    public static String membersRows(Map<String,Object> p){return members(p,false);}
    private static String statistics(Map<String,Object> p,boolean prizes){
        Map<String,String> query=query(p);
        List<Object> args=new ArrayList<>();String where=" WHERE 1=1"+MemberScopeQuery.memberFilter("p.member_id",(MemberScope)p.get("scope"),args);
        if(!text(query.get("activityId")).isEmpty()){where+=" AND p.activity_id=?";args.add(number(query.get("activityId")));}
        if(!text(query.get("type")).isEmpty()){where+=" AND p.activity_type=?";args.add(query.get("type"));}
        for(String key:List.of("status","prizeName","redemptionCode"))if(!text(query.get(key)).isEmpty()){
            where+=" AND p."+("prizeName".equals(key)?"prize_name":"redemptionCode".equals(key)?"redemption_code":"status")+"=?";args.add(query.get(key));
        }
        if(!text(query.get("keyword")).isEmpty()){where+=" AND (p.activity_name LIKE ? OR m.username LIKE ?)";args.add("%"+query.get("keyword")+"%");args.add("%"+query.get("keyword")+"%");}
        for(String key:List.of("startTime","endTime"))if(!text(query.get(key)).isEmpty()){
            where+=" AND p.created_at"+("startTime".equals(key)?">=?":"<?");
            args.add(OffsetDateTime.parse(query.get(key)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        }
        String table=" FROM compute_activity_participation p JOIN compute_member m ON m.id=p.member_id";
        if(!prizes)return SqlParameters.bind("SELECT p.currency,COUNT(*) participation_count,COUNT(DISTINCT p.member_id) member_count,COALESCE(SUM(CASE WHEN m.is_test=0 THEN p.amount ELSE 0 END),0) reserved_amount,COALESCE(SUM(CASE WHEN m.is_test=0 AND p.status='PAID' THEN p.amount ELSE 0 END),0) paid_amount,COALESCE(SUM(CASE WHEN m.is_test=0 AND p.status='PENDING_REDEMPTION' THEN p.amount ELSE 0 END),0) pending_amount,COALESCE(SUM(CASE WHEN m.is_test=0 AND p.status='MANUAL_REDEEMED' THEN p.amount ELSE 0 END),0) manual_amount"+table+where+" GROUP BY p.currency ORDER BY p.currency",args,p);
        return SqlParameters.bind("SELECT p.currency,p.prize_id,p.prize_name,COUNT(*) participation_count,COALESCE(SUM(CASE WHEN m.is_test=0 THEN p.amount ELSE 0 END),0) reserved_amount"+table+where+" GROUP BY p.currency,p.prize_id,p.prize_name ORDER BY p.currency,participation_count DESC",args,p);
    }
    public static String statisticsCurrencies(Map<String,Object> p){return statistics(p,false);}
    public static String statisticsPrizes(Map<String,Object> p){return statistics(p,true);}
}
