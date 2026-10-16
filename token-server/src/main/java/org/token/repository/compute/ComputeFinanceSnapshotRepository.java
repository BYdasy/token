package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeFinanceSnapshotMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeFinanceSnapshotService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeFinanceSnapshotRepository {
    private final ComputeFinanceSnapshotMapper mapper;

    @Autowired
    public ComputeFinanceSnapshotRepository(ComputeFinanceSnapshotMapper mapper) { this.mapper = mapper; }

    public ComputeFinanceSnapshotRepository(ComputeData data) {
        this(data.mapper(ComputeFinanceSnapshotMapper.class));
    }

    public List<Map<String, Object>> captureFinanceSnapshotRows(Object... args) {
        return ComputeData.normalizeRows(mapper.captureFinanceSnapshotRows(args));
    }

    public int captureFinanceSnapshotWrite(Object... args) {
        return mapper.captureFinanceSnapshotWrite(args);
    }
}
