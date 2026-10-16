package org.token.repository.compute;

import java.util.*;
import org.token.model.query.TeamReportPeriod;
import static org.token.repository.compute.ComputeData.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeTeamOverviewMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeTeamOverviewService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeTeamOverviewRepository {
    private final ComputeTeamOverviewMapper mapper;

    @Autowired
    public ComputeTeamOverviewRepository(ComputeTeamOverviewMapper mapper) { this.mapper = mapper; }

    public ComputeTeamOverviewRepository(ComputeData data) {
        this(data.mapper(ComputeTeamOverviewMapper.class));
    }

    public Map<String, Object> overviewMemberRow(Object... args) {
        return RepositoryResults.required(mapper.overviewMemberRow(args));
    }

    public long memberCount(long memberId, int supportedDepth) { return mapper.memberCount(memberId, supportedDepth); }
    public List<Map<String,Object>> structure(long memberId,int supportedDepth,LocalDateTime from,LocalDateTime to) { return ComputeData.normalizeRows(mapper.structure(memberId,supportedDepth,from,to)); }
    public List<Map<String,Object>> members(long memberId,int supportedDepth,Integer depth,int limit,long offset) { return ComputeData.normalizeRows(mapper.members(memberId,supportedDepth,depth,limit,offset)); }
    public Map<String,Object> memberSummary(long memberId,int supportedDepth,TeamReportPeriod period,LocalDate today,ZoneId zone) { return RepositoryResults.required(mapper.memberSummary(memberId,supportedDepth,period,today,zone)); }
    public List<Map<String,Object>> rewardSummary(long memberId,int supportedDepth,TeamReportPeriod period,LocalDate today,ZoneId zone) { return ComputeData.normalizeRows(mapper.rewardSummary(memberId,supportedDepth,period,Queries.start(today,zone),Queries.start(today.plusDays(1),zone))); }
    public List<Map<String,Object>> rewardTotals(long memberId,int supportedDepth) { return ComputeData.normalizeRows(mapper.rewardSummary(memberId,supportedDepth,null,null,null)); }
    public static final class Queries {
        private Queries() { }
    private static final String TREE = "WITH RECURSIVE team_tree(id,depth) AS ("
            + "SELECT id,0 FROM compute_member WHERE id=? UNION ALL "
            + "SELECT m.id,t.depth+1 FROM compute_member m JOIN team_tree t ON m.inviter_id=t.id WHERE t.depth<?) ";
    private static final String MEMBERS = " FROM team_tree t JOIN compute_member m ON m.id=t.id WHERE t.depth>0";

        private static String bind(String sql,List<?> args,Map<String,Object> p) { return org.token.repository.support.SqlParameters.bind(sql,args,p); }
        public static String memberCount(Map<String,Object> p) {
            return bind(TREE+"SELECT COUNT(*)"+MEMBERS,List.of(p.get("memberId"),p.get("supportedDepth")),p);
        }
        public static String structure(Map<String,Object> p) {
            return bind(TREE+"SELECT t.depth,COUNT(*) member_count,COUNT(CASE WHEN m.created_at>=? AND m.created_at<? THEN 1 END) new_members"+MEMBERS+" GROUP BY t.depth ORDER BY t.depth",List.of(p.get("memberId"),p.get("supportedDepth"),p.get("from"),p.get("to")),p);
        }
        public static String members(Map<String,Object> p) {
            List<Object> args=new ArrayList<>(List.of(p.get("memberId"),p.get("supportedDepth")));
            String filter="";
            if(p.get("depth")!=null){filter=" AND t.depth=?";args.add(p.get("depth"));}
            args.add(p.get("limit"));args.add(p.get("offset"));
            return bind(TREE+"SELECT m.username,p.username inviter_username,m.level,m.created_at,t.depth,l.image_url avatar_url FROM team_tree t JOIN compute_member m ON m.id=t.id JOIN compute_member p ON p.id=m.inviter_id LEFT JOIN compute_level l ON l.id=m.level WHERE t.depth>0"+filter+" ORDER BY t.depth,m.created_at DESC,m.id DESC LIMIT ? OFFSET ?",args,p);
        }
        public static String summary(Map<String,Object> p) {
            long memberId=(long)p.get("memberId");TeamReportPeriod period=(TeamReportPeriod)p.get("period");
            LocalDate today=(LocalDate)p.get("today");ZoneId zone=(ZoneId)p.get("zone");
        List<Object> args = new ArrayList<>(List.of(memberId, p.get("supportedDepth"), start(today, zone), start(today.plusDays(1), zone), period.start(), period.end()));
        List<String> columns = new ArrayList<>(List.of("COUNT(*) member_count", "COUNT(CASE WHEN t.depth=1 THEN 1 END) direct_count",
                "COUNT(CASE WHEN m.created_at>=? AND m.created_at<? THEN 1 END) today_new_members",
                "COUNT(CASE WHEN m.created_at>=? AND m.created_at<? THEN 1 END) period_new_members"));
        for (int index = 0; index < period.days().size(); index++) {
            LocalDate day = period.days().get(index);
            for (String condition : List.of("", " AND t.depth=1")) {
                columns.add("COUNT(CASE WHEN m.created_at>=? AND m.created_at<?" + condition + " THEN 1 END) " + (condition.isEmpty() ? "n" : "i") + index);
                args.add(start(day, zone)); args.add(start(day.plusDays(1), zone));
            }
        }

            return bind(TREE+"SELECT "+String.join(",",columns)+MEMBERS,args,p);
        }
        /** 只统计本人已入账的充值/收益返点。用 EXISTS 保证来源多表不会放大一条账本。 */
        public static String rewards(Map<String,Object> p) {
            long memberId=(long)p.get("memberId");
            TeamReportPeriod period=(TeamReportPeriod)p.get("period");
            List<Object> args=new ArrayList<>(List.of(memberId,p.get("supportedDepth")));
            List<String> columns=new ArrayList<>(List.of("l.currency", "COUNT(*) record_count", "COALESCE(SUM(l.amount),0) total_amount"));
            if (p.get("todayStart") != null && p.get("todayEnd") != null) {
                columns.add("COALESCE(SUM(CASE WHEN l.created_at>=? AND l.created_at<? THEN l.amount ELSE 0 END),0) today_amount");
                args.add(p.get("todayStart")); args.add(p.get("todayEnd"));
            }
            if (period != null) {
                columns.add("COALESCE(SUM(CASE WHEN l.created_at>=? AND l.created_at<? THEN l.amount ELSE 0 END),0) period_amount");
                args.add(period.start()); args.add(period.end());
                for(int index=0;index<period.days().size();index++) {
                    columns.add("COALESCE(SUM(CASE WHEN l.created_at>=? AND l.created_at<? THEN l.amount ELSE 0 END),0) d"+index);
                    args.add(start(period.days().get(index),period.zone()));
                    args.add(start(period.days().get(index).plusDays(1),period.zone()));
                }
            }
            args.add(memberId);
            String reference="CASE WHEN l.reference_id REGEXP '^[0-9]{1,19}$' THEN CAST(l.reference_id AS DECIMAL(20,0)) ELSE NULL END";
            String exact="r.member_id=l.member_id AND r.amount=l.amount AND CAST(r.currency AS BINARY(255))=CAST(l.currency AS BINARY(255))";
            String sql=TREE+"SELECT "+String.join(",",columns)+" FROM compute_ledger l WHERE l.member_id=?"
                    +" AND l.kind IN ('TEAM_REWARD','TEAM_REWARD_TEST') AND l.wallet_type='EARNINGS' AND l.frozen_change=0 AND l.amount>0"
                    +" AND EXISTS(SELECT 1 FROM compute_reward_award r JOIN team_tree t ON t.id=r.source_member_id AND t.depth>0"
                    +" WHERE r.id="+reference+" AND "+exact
                    +" AND CAST(l.event_key AS BINARY(255))=CAST(CONCAT('V9_REWARD:',r.id) AS BINARY(255))"
                    +" AND r.source_kind IN ('VIP_FIRST_DEPOSIT','VIP_DEPOSIT','DEPOSIT_DIRECT','VIP_INCOME','PRODUCT_INCOME')) GROUP BY l.currency";
            return bind(sql,args,p);
        }

        private static LocalDateTime start(LocalDate day,ZoneId zone) { return LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(),ZoneOffset.UTC); }
    }
}
