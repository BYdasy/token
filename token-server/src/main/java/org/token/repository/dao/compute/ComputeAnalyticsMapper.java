package org.token.repository.dao.compute;

import org.apache.ibatis.annotations.*;
import org.token.model.query.*;
import org.token.repository.compute.ComputeReportQueries;
import java.time.LocalDateTime;
import java.util.*;

@Mapper
public interface ComputeAnalyticsMapper {
    @SelectProvider(type=ComputeReportQueries.class,method="visibleUsername")
    List<Long> visibleUsername(@Param("username") String username,@Param("scope") MemberScope scope);
    @SelectProvider(type=ComputeReportQueries.class,method="period")
    List<Map<String,Object>> period(@Param("resource") String resource,@Param("buckets") List<ReportBucket> buckets,@Param("from") LocalDateTime from,@Param("to") LocalDateTime to,@Param("filter") ReportMemberFilter filter);
    @SelectProvider(type=ComputeReportQueries.class,method="people")
    List<Map<String,Object>> people(@Param("from") LocalDateTime from,@Param("to") LocalDateTime to,@Param("filter") ReportMemberFilter filter);
}
