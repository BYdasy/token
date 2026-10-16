package org.token.model.query;

import java.util.Map;

/** 管理业务已复核代理入口后的查询条件；不包含SQL或认证对象。 */
public record ManagementMemberQuery(MemberScope scope,Map<String,String> filters) {
    public ManagementMemberQuery { filters=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(filters)); }
}
