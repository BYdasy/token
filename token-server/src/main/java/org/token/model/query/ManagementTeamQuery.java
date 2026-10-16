package org.token.model.query;

import java.util.Map;

/** 已授权团队入口与业务确认的层级范围，仅供本次报表查询使用。 */
public record ManagementTeamQuery(long root,Map<String,String> filters,int exact,int maximum,boolean includeSelf) {
    public ManagementTeamQuery { filters=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(filters)); }
}
