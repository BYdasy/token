package org.token.repository.compute;

import org.token.exception.BusinessException;
import org.token.model.query.MemberScope;
import org.token.repository.support.SqlParameters;
import java.time.*;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 活动目录与参与记录的命名数据库查询。 */
public final class ComputeActivityQueries {
    private ComputeActivityQueries() { }
    /** 参数错误在 Repository 边界直接作为业务异常抛出，不进入 Provider 反射调用。 */
    public static void validate(Map<String,String> query,boolean catalog,boolean publicOnly) {
        limit(query);offset(query);
        if(catalog&&!publicOnly&&!text(query.get("status")).isEmpty()
                &&!Set.of("ENABLED","DISABLED").contains(text(query.get("status"))))throw invalid();
        String start=query.getOrDefault("startTime",catalog?null:query.get("startDate"));
        String end=query.getOrDefault("endTime",catalog?null:query.get("endDate"));
        if(!text(start).isEmpty())time(start);
        if(!text(end).isEmpty())time(end);
    }
    @SuppressWarnings("unchecked") private static Map<String,String> query(Map<String,Object> p){return (Map<String,String>)p.get("query");}
    private static String activityPage(Map<String,Object> p,boolean count){
        Map<String,String> query=query(p);boolean publicOnly=Boolean.TRUE.equals(p.get("publicOnly"));
        List<Object> args = new ArrayList<>();
        String where = " WHERE (type<>'WHEEL' OR id=?)";args.add(number(p.get("primaryWheel")));
        if (publicOnly) where += " AND enabled=1";
        if (!text(query.get("type")).isEmpty()) { where += " AND type=?"; args.add(query.get("type")); }
        if (!text(query.get("keyword")).isEmpty()) { where += " AND name LIKE ?"; args.add("%" + text(query.get("keyword")) + "%"); }
        if (!publicOnly && !text(query.get("status")).isEmpty()) {
            String status = text(query.get("status"));
            if (!Set.of("ENABLED", "DISABLED").contains(status)) throw invalid();
            where += " AND enabled=?"; args.add("ENABLED".equals(status));
        }
        if (!text(query.get("startTime")).isEmpty()) { where += " AND created_at>=?"; args.add(time(query.get("startTime"))); }
        if (!text(query.get("endTime")).isEmpty()) { where += " AND created_at<?"; args.add(time(query.get("endTime"))); }
        if(count)return SqlParameters.bind("SELECT COUNT(*) FROM compute_activity"+where,args,p);
        args.add(limit(query));args.add(offset(query));
        return SqlParameters.bind("SELECT * FROM compute_activity"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",args,p);
    }
    public static String pageCount(Map<String,Object> p){return activityPage(p,true);}
    public static String pageRows(Map<String,Object> p){return activityPage(p,false);}
    public static String participationCount(Map<String,Object> p){
        String sql="SELECT COUNT(*) FROM compute_activity_participation WHERE activity_id=#{id} AND member_id=#{memberId} AND wheel_round=#{round}";
        return p.get("start")==null?sql:sql+" AND created_at>=#{start} AND created_at<#{end}";
    }
    private static String participations(Map<String,Object> p,boolean count){
        Long memberId=(Long)p.get("memberId");Map<String,String> query=query(p);
        List<Object> args = new ArrayList<>(); String where = " WHERE 1=1";
        if (memberId != null) { where += " AND p.member_id=?"; args.add(memberId); }
        else where += MemberScopeQuery.memberFilter("p.member_id",(MemberScope)p.get("scope"),args);
        if (!text(query.get("activityId")).isEmpty()) { where += " AND p.activity_id=?"; args.add(number(query.get("activityId"))); }
        if (memberId == null && !text(query.get("memberId")).isEmpty()) { where += " AND p.member_id=?"; args.add(number(query.get("memberId"))); }
        if (!text(query.get("type")).isEmpty()) { where += " AND p.activity_type=?"; args.add(query.get("type")); }
        for (String key : List.of("status", "prizeName", "redemptionCode")) if (!text(query.get(key)).isEmpty()) {
            where += " AND p." + ("prizeName".equals(key) ? "prize_name" : "redemptionCode".equals(key) ? "redemption_code" : "status") + "=?";args.add(query.get(key));
        }
        if (!text(query.get("keyword")).isEmpty()) {
            where += " AND (p.activity_name LIKE ? OR m.username LIKE ?)";
            String keyword = "%" + text(query.get("keyword")) + "%"; args.add(keyword); args.add(keyword);
        }
        String start = query.getOrDefault("startTime", query.get("startDate")), end = query.getOrDefault("endTime", query.get("endDate"));
        if (!text(start).isEmpty()) { where += " AND p.created_at>=?"; args.add(time(start)); }
        if (!text(end).isEmpty()) { where += " AND p.created_at<?"; args.add(time(end)); }
        String table = " FROM compute_activity_participation p LEFT JOIN compute_member m ON m.id=p.member_id";
        if(count)return SqlParameters.bind("SELECT COUNT(*)"+table+where,args,p);
        args.add(limit(query));args.add(offset(query));
        return SqlParameters.bind("SELECT p.*,m.username"+table+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",args,p);
    }
    public static String participationsCount(Map<String,Object> p){return participations(p,true);}
    public static String participationsRows(Map<String,Object> p){return participations(p,false);}
    private static int limit(Map<String, String> query) { return (int) integer(query.getOrDefault("size", "20"), 1, 100); }
    private static long offset(Map<String, String> query) { return (integer(query.getOrDefault("page", "1"), 1, 100000) - 1) * limit(query); }
    private static long integer(Object value, long minimum, long maximum) { long number = number(value); if (number < minimum || number > maximum) throw invalid(); return number; }
    private static LocalDateTime time(Object value) { try { return OffsetDateTime.parse(text(value)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); } catch (RuntimeException error) { throw new BusinessException("server.compute.activity.invalidTime"); } }
    private static BusinessException invalid(){return new BusinessException("server.compute.activity.invalidInput");}
}
