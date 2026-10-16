package org.token.repository.compute;

import org.token.model.query.ActivityDepositQuery;
import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeActivityInviteChancesMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeActivityInviteChances 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeActivityInviteChancesRepository {
    private final ComputeActivityInviteChancesMapper mapper;

    @Autowired
    public ComputeActivityInviteChancesRepository(ComputeActivityInviteChancesMapper mapper) { this.mapper = mapper; }

    public ComputeActivityInviteChancesRepository(ComputeData data) {
        this(data.mapper(ComputeActivityInviteChancesMapper.class));
    }

    public Long totalActivityInviteGrantValue(Object... args) {
        return RepositoryResults.scalar(mapper.totalActivityInviteGrantValue(args), Long.class);
    }

    public List<Map<String, Object>> pendingActivityInviteGrantRows(Object... args) {
        return ComputeData.normalizeRows(mapper.pendingActivityInviteGrantRows(args));
    }
    public List<Map<String,Object>> invitedMembers(ActivityDepositQuery query,BigDecimal minimum,long limit){return ComputeData.normalizeRows(mapper.invitedMembers(ComputeData.fields("query",query,"minimum",minimum,"limit",limit)));}
}
