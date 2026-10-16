package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeLevelPresentationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeLevelPresentation 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeLevelPresentationRepository {
    private final ComputeLevelPresentationMapper mapper;

    @Autowired
    public ComputeLevelPresentationRepository(ComputeLevelPresentationMapper mapper) { this.mapper = mapper; }

    public ComputeLevelPresentationRepository(ComputeData data) {
        this(data.mapper(ComputeLevelPresentationMapper.class));
    }

    public List<Map<String, Object>> applyFeeRuleRows(Object... args) {
        return ComputeData.normalizeRows(mapper.applyFeeRuleRows(args));
    }
}
