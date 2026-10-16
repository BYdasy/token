package org.token.repository.compute;

import org.token.repository.dao.compute.ComputeHomeFinanceMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.support.RepositoryResults;
import org.token.model.query.ReportMemberFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class ComputeHomeFinanceRepository {
    private final ComputeHomeFinanceMapper mapper;
    @Autowired public ComputeHomeFinanceRepository(ComputeHomeFinanceMapper mapper) { this.mapper=mapper; }
    public ComputeHomeFinanceRepository(ComputeData data) { this(data.mapper(ComputeHomeFinanceMapper.class)); }
    public long memberCount(ReportMemberFilter filter) { return mapper.memberCount(filter); }
    public long activatedMembers(ReportMemberFilter filter) { return mapper.activatedMembers(filter); }
    public long depositors(ReportMemberFilter filter) { return mapper.depositors(filter); }
    public Map<String,Object> totals(String kind,ReportMemberFilter filter) { return RepositoryResults.required(mapper.totals(kind,filter)); }
    public List<Map<String,Object>> balances(ReportMemberFilter filter) { return ComputeData.normalizeRows(mapper.balances(filter)); }
    public long detailCount(String kind,ReportMemberFilter filter) { return mapper.detailCount(kind,filter); }
    public List<Map<String,Object>> details(String kind,ReportMemberFilter filter,int size,long offset) { return ComputeData.normalizeRows(mapper.details(kind,filter,size,offset)); }
}
