package org.token.model.query;

/** 服务本次已核验的报表会员范围，不包含 SQL 或可执行排序表达式。 */
public record ReportMemberFilter(MemberScope scope, Long groupId, Long teamMemberId) { }
