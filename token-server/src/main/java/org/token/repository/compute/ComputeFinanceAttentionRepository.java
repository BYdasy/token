package org.token.repository.compute;

import org.token.repository.dao.compute.ComputeFinanceAttentionMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.model.query.MemberScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class ComputeFinanceAttentionRepository {
    private final ComputeFinanceAttentionMapper mapper;
    @Autowired public ComputeFinanceAttentionRepository(ComputeFinanceAttentionMapper mapper) { this.mapper=mapper; }
    public ComputeFinanceAttentionRepository(ComputeData data) { this(data.mapper(ComputeFinanceAttentionMapper.class)); }
    public List<Map<String,Object>> snapshot(boolean withdrawal,boolean gateway,String merchantId,String environment,MemberScope scope) {
        return ComputeData.normalizeRows(mapper.snapshot(withdrawal,gateway,merchantId,environment,scope));
    }
}
