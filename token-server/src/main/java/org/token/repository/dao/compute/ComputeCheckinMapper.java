package org.token.repository.dao.compute;

import java.util.*;
import org.token.model.query.MemberScope;
import org.token.repository.compute.ComputeCheckinRepository;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeCheckinService 的参数化数据库语句。 */
@Mapper
public interface ComputeCheckinMapper {

    @Select("SELECT order_no,checkin_date,streak_days,reward_token,created_at FROM compute_checkin WHERE member_id=#{args[0]} ORDER BY id DESC LIMIT 31")
    List<Map<String, Object>> statusCheckinRows(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} AND enabled=1 FOR UPDATE")
    List<Map<String, Object>> checkinMemberRow(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_checkin WHERE member_id=#{args[0]} AND checkin_date=#{args[1]}")
    List<Map<String, Object>> checkinCheckinCount(@Param("args") Object[] args);

    @Select("SELECT checkin_date,streak_days FROM compute_checkin WHERE member_id=#{args[0]} ORDER BY id DESC LIMIT 1")
    List<Map<String, Object>> checkinCheckinRows(@Param("args") Object[] args);

    @SelectProvider(type=ComputeCheckinRepository.Queries.class, method="count")
    long recordCount(@Param("scope") MemberScope scope,@Param("username") String username);
    @SelectProvider(type=ComputeCheckinRepository.Queries.class, method="records")
    List<Map<String,Object>> records(@Param("scope") MemberScope scope,@Param("username") String username,@Param("limit") int limit,@Param("offset") int offset);
}
