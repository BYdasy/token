package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import org.token.model.query.TeamReportPeriod;
import org.token.repository.compute.ComputeTeamOverviewRepository;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeTeamOverviewService 的参数化数据库语句。 */
@Mapper
public interface ComputeTeamOverviewMapper {

    @Select("SELECT username FROM compute_member WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> overviewMemberRow(@Param("args") Object[] args);

    @SelectProvider(type=ComputeTeamOverviewRepository.Queries.class,method="memberCount")
    long memberCount(@Param("memberId") long memberId,@Param("supportedDepth") int supportedDepth);
    @SelectProvider(type=ComputeTeamOverviewRepository.Queries.class,method="structure")
    List<Map<String,Object>> structure(@Param("memberId") long memberId,@Param("supportedDepth") int supportedDepth,@Param("from") LocalDateTime from,@Param("to") LocalDateTime to);
    @SelectProvider(type=ComputeTeamOverviewRepository.Queries.class,method="members")
    List<Map<String,Object>> members(@Param("memberId") long memberId,@Param("supportedDepth") int supportedDepth,@Param("depth") Integer depth,@Param("limit") int limit,@Param("offset") long offset);
    @SelectProvider(type=ComputeTeamOverviewRepository.Queries.class,method="summary")
    List<Map<String,Object>> memberSummary(@Param("memberId") long memberId,@Param("supportedDepth") int supportedDepth,@Param("period") TeamReportPeriod period,@Param("today") LocalDate today,@Param("zone") ZoneId zone);
    @SelectProvider(type=ComputeTeamOverviewRepository.Queries.class,method="rewards")
    List<Map<String,Object>> rewardSummary(@Param("memberId") long memberId,@Param("supportedDepth") int supportedDepth,@Param("period") TeamReportPeriod period,@Param("todayStart") LocalDateTime todayStart,@Param("todayEnd") LocalDateTime todayEnd);
}
