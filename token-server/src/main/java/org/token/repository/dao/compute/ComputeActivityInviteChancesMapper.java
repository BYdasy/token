package org.token.repository.dao.compute;

import org.token.repository.compute.ComputeActivityQualificationQueries;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeActivityInviteChances 的参数化数据库语句。 */
@Mapper
public interface ComputeActivityInviteChancesMapper {

    @Select("SELECT COALESCE(SUM(awarded_count),0) AS repository_value FROM compute_activity_invite_grant WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND member_id=#{args[2]}")
    List<Map<String, Object>> totalActivityInviteGrantValue(@Param("args") Object[] args);

    @Select("SELECT member_threshold FROM compute_activity_invite_grant WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND member_id=#{args[2]}")
    List<Map<String, Object>> pendingActivityInviteGrantRows(@Param("args") Object[] args);
    @SelectProvider(type=ComputeActivityQualificationQueries.class,method="invitedMembers")
    List<Map<String,Object>> invitedMembers(Map<String,Object> parameters);
}
