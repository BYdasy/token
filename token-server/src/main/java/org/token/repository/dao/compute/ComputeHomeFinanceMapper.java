package org.token.repository.dao.compute;

import org.apache.ibatis.annotations.*;
import org.token.model.query.ReportMemberFilter;
import org.token.repository.compute.ComputeReportQueries;
import java.util.*;

@Mapper
public interface ComputeHomeFinanceMapper {
    @SelectProvider(type=ComputeReportQueries.class,method="homeMembers")
    long memberCount(@Param("filter") ReportMemberFilter filter);
    @SelectProvider(type=ComputeReportQueries.class,method="homeActivated")
    long activatedMembers(@Param("filter") ReportMemberFilter filter);
    @SelectProvider(type=ComputeReportQueries.class,method="homeDepositors")
    long depositors(@Param("filter") ReportMemberFilter filter);
    @SelectProvider(type=ComputeReportQueries.class,method="homeTotals")
    List<Map<String,Object>> totals(@Param("kind") String kind,@Param("filter") ReportMemberFilter filter);
    @SelectProvider(type=ComputeReportQueries.class,method="homeBalances")
    List<Map<String,Object>> balances(@Param("filter") ReportMemberFilter filter);
    @SelectProvider(type=ComputeReportQueries.class,method="detailCount")
    long detailCount(@Param("kind") String kind,@Param("filter") ReportMemberFilter filter);
    @SelectProvider(type=ComputeReportQueries.class,method="details")
    List<Map<String,Object>> details(@Param("kind") String kind,@Param("filter") ReportMemberFilter filter,@Param("size") int size,@Param("offset") long offset);
}
