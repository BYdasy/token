package org.token.repository.dao.compute;

import java.util.*;
import org.token.model.query.MemberScope;
import org.token.repository.compute.MemberScopeQuery;
import org.token.repository.support.SqlParameters;
import static org.token.repository.compute.ComputeData.*;

/** 内容查询只选择本类固定语句；客户端值全部通过 MyBatis 参数绑定。 */
public final class ComputeContentQuerySql {
    private static final String PUBLIC_ORDER = "c.pinned DESC,c.sort DESC,COALESCE(c.starts_at,c.created_at) DESC,c.id DESC";
    private static final String PERSONAL = "(c.kind='MESSAGE' OR (c.kind='TASK' AND COALESCE(c.task_mode,CASE c.task_type WHEN 'INVESTMENT' THEN 1 WHEN 'WITHDRAWAL' THEN 2 ELSE 4 END) IN (4,5,6)))";
    private ComputeContentQuerySql() { }

    public static String select(Map<String,Object> p) { return build(p,false); }
    public static String count(Map<String,Object> p) { return build(p,true); }

    @SuppressWarnings("unchecked")
    private static String build(Map<String,Object> p, boolean count) {
        String mode=text(p.get("mode"));
        Map<String,String> q=(Map<String,String>)p.getOrDefault("query",Map.of());
        MemberScope scope=(MemberScope)p.get("scope");
        List<Object> args=new ArrayList<>();
        String table="compute_content c", columns="c.*", order=PUBLIC_ORDER;
        StringBuilder where=new StringBuilder();
        switch(mode) {
            case "ADMIN_PAGE" -> {
                where.append(" WHERE c.deleted=0").append(MemberScopeQuery.ownerFilter("c.owner_member_id",scope,args,false));
                where.append(" AND c.kind=?");args.add(p.get("kind"));
                if(p.get("contentKey")!=null){where.append(" AND c.content_key=?");args.add(p.get("contentKey"));}
                if(p.get("keyword")!=null){where.append(" AND c.title LIKE ?");args.add("%"+p.get("keyword")+"%");}
                if(!text(q.get("status")).isEmpty()){where.append(" AND c.status=?");args.add(q.get("status"));}
                if(!text(q.get("enabled")).isEmpty()){where.append(" AND c.enabled=?");args.add(enabled(q.get("enabled")));}
                if(p.get("start")!=null){where.append(" AND c.created_at>=?");args.add(p.get("start"));}
                if(p.get("end")!=null){where.append(" AND c.created_at<=?");args.add(p.get("end"));}
                order="c.created_at DESC,c.id DESC";
            }
            case "ADMIN_ROW" -> {
                where.append(" WHERE c.id=? AND c.deleted=0");args.add(p.get("id"));
                where.append(MemberScopeQuery.ownerFilter("c.owner_member_id",scope,args,false));
                order="";
            }
            case "RECIPIENT_PAGE", "RECIPIENT_COUNT" -> {
                table="RECIPIENT_COUNT".equals(mode)?"compute_content_recipient r":"compute_content_recipient r JOIN compute_member m ON m.id=r.member_id";columns="r.*,m.username";order="r.member_id DESC";
                where.append(" WHERE r.content_id=?");args.add(p.get("id"));
                if("RECIPIENT_COUNT".equals(mode)){where.append(" AND r.member_id=?");args.add(p.get("memberId"));}
                where.append(MemberScopeQuery.memberFilter("r.member_id",scope,args));
                if(!text(q.get("memberId")).isEmpty()){where.append(" AND r.member_id=?");args.add(number(q.get("memberId")));}
                if(p.get("username")!=null){where.append(" AND m.username LIKE ?");args.add("%"+p.get("username")+"%");}
                if(!text(q.get("read")).isEmpty())where.append(enabled(q.get("read"))?" AND r.read_at IS NOT NULL":" AND r.read_at IS NULL");
                if(!text(q.get("completed")).isEmpty())where.append(enabled(q.get("completed"))?" AND r.completed_at IS NOT NULL":" AND r.completed_at IS NULL");
            }
            case "USER_PAGE", "USER_ROW" -> {
                Object memberId=p.get("memberId");String kind=text(p.get("kind"));
                where.append(" WHERE c.deleted=0 AND c.enabled=1 AND c.status='PUBLISHED'");
                if("USER_ROW".equals(mode)){where.append(" AND c.id=?");args.add(p.get("id"));}
                if("USER_PAGE".equals(mode)) {
                    if("MESSAGE".equals(kind))where.append(" AND ").append(PERSONAL);
                    else {where.append(" AND c.kind=?");args.add(kind);}
                }
                where.append(" AND (c.starts_at IS NULL OR c.starts_at<=?) AND (c.ends_at IS NULL OR c.ends_at>?)");args.add(p.get("startNow"));args.add(p.get("endNow"));
                if(memberId!=null) {
                    table+=" JOIN compute_content_recipient r ON r.content_id=c.id";columns+=",r.read_at,r.completed_at";
                    where.append(" AND r.member_id=?");args.add(memberId);
                    if("USER_ROW".equals(mode))where.append(" AND c.kind IN ('MESSAGE','TASK')");
                } else if("USER_ROW".equals(mode))where.append(" AND c.kind IN ('ANNOUNCEMENT','ARTICLE')");
                if("USER_ROW".equals(mode)&&!kind.isEmpty()) {
                    if("MESSAGE".equals(kind))where.append(" AND ").append(PERSONAL);
                    else {where.append(" AND c.kind=?");args.add(kind);}
                }
                if("USER_PAGE".equals(mode)&&memberId==null&&enabled(q.get("showHome")))where.append(" AND c.show_home=1");
                if(Boolean.TRUE.equals(p.get("helpView")))where.append(" AND c.content_key IN ('GENERAL','FAQ')");
                else if("ARTICLE".equals(kind)){where.append(" AND c.content_key=?");args.add(p.get("contentKey"));}
                List<Integer> modes=(List<Integer>)p.getOrDefault("taskModes",List.of());
                if(!modes.isEmpty()) {
                    where.append(" AND COALESCE(c.task_mode,CASE c.task_type WHEN 'INVESTMENT' THEN 1 WHEN 'WITHDRAWAL' THEN 2 ELSE 4 END) IN (")
                            .append(MemberScopeQuery.placeholders(modes.size())).append(") AND ")
                            .append(Set.of("INVEST","WITHDRAW").contains(text(q.get("scene")).toUpperCase(Locale.ROOT))?"r.restriction_released_at":"r.read_at").append(" IS NULL");
                    args.addAll(modes);
                    if("HOME".equalsIgnoreCase(q.get("scene")))where.append(" AND c.show_home=1");
                }
                if("USER_ROW".equals(mode))order="";
            }
            case "PUBLIC_POPUPS", "MEMBER_POPUPS" -> {
                if("MEMBER_POPUPS".equals(mode)) {
                    table+=" LEFT JOIN compute_content_recipient r ON r.content_id=c.id AND r.member_id=? LEFT JOIN compute_content_popup_seen seen ON seen.content_id=c.id AND seen.member_id=? AND seen.content_version=c.content_version";
                    args.add(p.get("memberId"));args.add(p.get("memberId"));columns+=",r.read_at,r.completed_at";
                }
                where.append(" WHERE c.deleted=0 AND c.enabled=1 AND c.status='PUBLISHED' AND c.home_popup_enabled=1");
                if("PUBLIC_POPUPS".equals(mode))where.append(" AND c.kind='ANNOUNCEMENT'");
                else where.append(" AND (c.kind='ANNOUNCEMENT' OR seen.content_id IS NULL) AND (c.kind='ANNOUNCEMENT' OR (c.kind='MESSAGE' AND r.member_id IS NOT NULL))");
                where.append(" AND (c.starts_at IS NULL OR c.starts_at<=?) AND (c.ends_at IS NULL OR c.ends_at>?)");args.add(p.get("startNow"));args.add(p.get("endNow"));
            }
            case "ACTIVE_MEMBERS" -> {
                List<Long> ids=(List<Long>)p.get("ids");table="compute_member m";
                where.append(" WHERE m.id IN (").append(MemberScopeQuery.placeholders(ids.size())).append(") AND m.enabled=1");args.addAll(ids);
                where.append(MemberScopeQuery.memberFilter("m.id",scope,args));
            }
            case "ACTIVE_GROUP" -> {
                table="compute_member_group g";where.append(" WHERE g.id=? AND g.enabled=1");args.add(p.get("id"));
                where.append(MemberScopeQuery.ownerFilter("g.owner_member_id",scope,args,true));
            }
            case "DELIVERY" -> {
                Map<String,Object> row=(Map<String,Object>)p.get("content");table="compute_member m";columns="m.id";order="m.id";
                where.append(" WHERE m.enabled=1 AND m.id>? AND m.id<=?");args.add(number(row.get("processedId")));args.add(number(row.get("audienceMaxId")));
                Long memberOwner=(Long)p.get("memberOwner");
                if(memberOwner!=null)where.append(MemberScopeQuery.descendantCondition("m.id",List.of(memberOwner),args));
                switch(text(row.get("targetType"))) {
                    case "USER" -> {List<Long> ids=(List<Long>)p.get("ids");where.append(" AND m.id IN (").append(MemberScopeQuery.placeholders(ids.size())).append(')');args.addAll(ids);}
                    case "GROUP" -> {if(number(row.get("groupId"))==0)where.append(" AND (m.group_id IS NULL OR m.group_id=0)");else{where.append(" AND m.group_id=?");args.add(row.get("groupId"));}}
                    case "AGENT" -> {Object target=row.get("targetMemberId");if(target!=null&&!text(target).isEmpty())where.append(MemberScopeQuery.descendantCondition("m.id",List.of(number(target)),args));else throw new org.token.exception.BusinessException("server.compute.content.targetInvalid");}
                    default -> { }
                }
            }
            default -> throw new IllegalArgumentException("未知内容查询");
        }
        String sql="SELECT "+(count?"COUNT(*)":columns)+" FROM "+table+where;
        if(!count) {
            if(!order.isEmpty())sql+=" ORDER BY "+order;
            if("DELIVERY".equals(mode))sql+=" LIMIT 200 FOR UPDATE";
            else if(p.get("limit")!=null){sql+=" LIMIT ? OFFSET ?";args.add(p.get("limit"));args.add(p.get("offset"));}
            else if(Boolean.TRUE.equals(p.get("lock")))sql+=" FOR UPDATE";
        }
        return SqlParameters.bind(sql,args,p);
    }
}
