package org.token.repository.dao.provider;

import org.apache.ibatis.jdbc.SQL;
import org.token.model.request.FilterParams;
import org.token.model.request.QueryParams;
import java.util.Map;

/** 系统列表查询，所有筛选值使用参数绑定，排序只接受各表的固定列名。 */
public final class SystemPageSql {
    private SystemPageSql() { }

    public static String roles(Map<String, Object> values) {
        QueryParams p = (QueryParams) values.get("query");
        FilterParams f = filter(p);
        SQL sql = new SQL().SELECT("id,name,remarks,actionTime,isSelf AS isSuper,role").FROM("user_role");
        if (f != null) {
            like(sql, "name", "name", f.getName());
            like(sql, "remarks", "userRemark", f.getUserRemark());
            range(sql, "actionTime", f);
        }
        order(sql, p, Map.of("id", "id", "name", "name", "remarks", "remarks", "actionTime", "actionTime", "isSuper", "isSelf", "role", "role"), "actionTime DESC,id ASC", false);
        return sql.toString();
    }

    public static String languages(Map<String, Object> values) {
        QueryParams p = (QueryParams) values.get("query");
        FilterParams f = filter(p);
        SQL sql = new SQL().SELECT("*").FROM("translate_language");
        if (f != null) {
            eq(sql, "lang", "key", f.getKey()); eq(sql, "status", "status", f.getStatus()); range(sql, "update_time", f);
            if (text(f.getName())) sql.WHERE("(LOWER(name) LIKE #{namePattern} ESCAPE '!' OR LOWER(chinese_name) LIKE #{namePattern} ESCAPE '!' OR LOWER(native_name) LIKE #{namePattern} ESCAPE '!')");
        }
        return sql.ORDER_BY("sort_no ASC,id ASC").toString();
    }

    public static String translations(Map<String, Object> values) {
        QueryParams p = (QueryParams) values.get("query");
        FilterParams f = filter(p);
        SQL sql = new SQL().SELECT("*").FROM("translates").WHERE("lang='zh'");
        if (f != null) {
            like(sql, "tkey", "key", f.getKey()); like(sql, "title", "langTitle", f.getLangTitle());
            like(sql, "tvalue", "content", f.getContent()); eq(sql, "prop", "prop", f.getProp()); range(sql, "action_time", f);
        }
        if (values.get("selectedType") == null) sql.WHERE("type<>0", "type NOT IN (5,7)");
        else sql.WHERE("type=#{selectedType}");
        java.util.Collection<?> keys = (java.util.Collection<?>) values.get("systemKeys");
        if (keys != null && !keys.isEmpty()) {
            String bound = java.util.stream.IntStream.range(0, keys.size()).mapToObj(i -> "#{systemKeys[" + i + "]}").collect(java.util.stream.Collectors.joining(","));
            sql.WHERE("(type<>3 OR tkey NOT IN (" + bound + "))");
            if (!Boolean.TRUE.equals(values.get("global"))) sql.WHERE("tkey NOT IN (" + bound + ")");
        }
        return sql.ORDER_BY("action_time DESC,id DESC").toString();
    }

    private static FilterParams filter(QueryParams p) { return p == null ? null : p.getFilterParams(); }
    private static boolean text(String s) { return s != null && !s.trim().isEmpty(); }
    private static void eq(SQL sql, String column, String property, String value) { if (text(value)) sql.WHERE(column + " = TRIM(#{query.filterParams." + property + "})"); }
    private static void like(SQL sql, String column, String property, String value) { if (text(value)) sql.WHERE(column + " LIKE CONCAT('%',TRIM(#{query.filterParams." + property + "}),'%')"); }
    private static void range(SQL sql, String column, FilterParams f) {
        if (text(f.getStartDate())) sql.WHERE(column + " >= TRIM(#{query.filterParams.startDate})");
        if (text(f.getEndDate())) sql.WHERE(column + " <= TRIM(#{query.filterParams.endDate})");
    }
    private static void order(SQL sql, QueryParams p, Map<String, String> columns, String fallback, boolean tieBreak) {
        String property = p == null || p.getSortColumn() == null ? "" : String.valueOf(p.getSortColumn().getProp()).trim();
        String column = columns.get(property);
        if (column == null) { sql.ORDER_BY(fallback); return; }
        boolean descending = "descending".equalsIgnoreCase(String.valueOf(p.getSortColumn().getOrder()).trim());
        sql.ORDER_BY(column + (descending ? " DESC" : " ASC"));
        if (tieBreak && !"id".equals(property)) sql.ORDER_BY("id DESC");
    }
}
