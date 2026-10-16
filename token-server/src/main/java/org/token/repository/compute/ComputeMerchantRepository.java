package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeMerchantMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeMerchantService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeMerchantRepository {
    private final ComputeMerchantMapper mapper;

    @Autowired
    public ComputeMerchantRepository(ComputeMerchantMapper mapper) { this.mapper = mapper; }

    public ComputeMerchantRepository(ComputeData data) {
        this(data.mapper(ComputeMerchantMapper.class));
    }

    public List<Map<String, Object>> synchronizationMerchantSnapshotRows(Object... args) {
        return ComputeData.normalizeRows(mapper.synchronizationMerchantSnapshotRows(args));
    }

    public int synchronizeMerchantSnapshotWrite(Object... args) {
        return mapper.synchronizeMerchantSnapshotWrite(args);
    }

    public int synchronizeMerchantSnapshotWrite2(Object... args) {
        return mapper.synchronizeMerchantSnapshotWrite2(args);
    }

    public int synchronizeMerchantSnapshotWrite3(Object... args) {
        return mapper.synchronizeMerchantSnapshotWrite3(args);
    }
}
