package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeFinanceSoundMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeFinanceSoundService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeFinanceSoundRepository {
    private final ComputeFinanceSoundMapper mapper;

    @Autowired
    public ComputeFinanceSoundRepository(ComputeFinanceSoundMapper mapper) { this.mapper = mapper; }

    public ComputeFinanceSoundRepository(ComputeData data) {
        this(data.mapper(ComputeFinanceSoundMapper.class));
    }

    public Map<String, Object> settingsFinanceSoundConfigRow(Object... args) {
        return RepositoryResults.required(mapper.settingsFinanceSoundConfigRow(args));
    }

    public Map<String, Object> settingsFinanceSoundConfigRow2(Object... args) {
        return RepositoryResults.required(mapper.settingsFinanceSoundConfigRow2(args));
    }

    public int saveFinanceSoundConfigWrite(Object... args) {
        return mapper.saveFinanceSoundConfigWrite(args);
    }
}
