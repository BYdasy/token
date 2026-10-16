package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeMcagCollectionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeMcagCollectionService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeMcagCollectionRepository {
    private final ComputeMcagCollectionMapper mapper;

    @Autowired
    public ComputeMcagCollectionRepository(ComputeMcagCollectionMapper mapper) { this.mapper = mapper; }

    public ComputeMcagCollectionRepository(ComputeData data) {
        this(data.mapper(ComputeMcagCollectionMapper.class));
    }

    public List<Map<String, Object>> applyMcagAddressRows(Object... args) {
        return ComputeData.normalizeRows(mapper.applyMcagAddressRows(args));
    }

    public Map<String, Object> applyMcagAddressRow(Object... args) {
        return RepositoryResults.required(mapper.applyMcagAddressRow(args));
    }

    public Map<String, Object> applyMemberRow(Object... args) {
        return RepositoryResults.required(mapper.applyMemberRow(args));
    }

    public Map<String, Object> applyMcagCursorRow(Object... args) {
        return RepositoryResults.required(mapper.applyMcagCursorRow(args));
    }

    public List<Map<String, Object>> applyMcagCollectionBatchRows(Object... args) {
        return ComputeData.normalizeRows(mapper.applyMcagCollectionBatchRows(args));
    }

    public int applyMcagCollectionBatchWrite(Object... args) {
        return mapper.applyMcagCollectionBatchWrite(args);
    }
}
