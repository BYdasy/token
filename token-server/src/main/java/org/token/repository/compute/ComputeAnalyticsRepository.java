package org.token.repository.compute;

import org.token.repository.dao.compute.ComputeAnalyticsMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.support.RepositoryResults;
import org.token.model.query.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.*;

@Repository
public class ComputeAnalyticsRepository {
    private final ComputeAnalyticsMapper mapper;
    @Autowired public ComputeAnalyticsRepository(ComputeAnalyticsMapper mapper) { this.mapper=mapper; }
    public ComputeAnalyticsRepository(ComputeData data) { this(data.mapper(ComputeAnalyticsMapper.class)); }
    public List<Long> visibleUsername(String username,MemberScope scope) { return mapper.visibleUsername(username,scope); }
    public List<Map<String,Object>> period(String resource,List<ReportBucket> buckets,LocalDateTime from,LocalDateTime to,ReportMemberFilter filter) { return RepositoryResults.rawRows(mapper.period(resource,buckets,from,to,filter)); }
    public Map<String,Object> people(LocalDateTime from,LocalDateTime to,ReportMemberFilter filter) { return RepositoryResults.singleRow(mapper.people(from,to,filter)); }
}
