package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeProductPurchasePolicyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeProductPurchasePolicy 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeProductPurchasePolicyRepository {
    private final ComputeProductPurchasePolicyMapper mapper;

    @Autowired
    public ComputeProductPurchasePolicyRepository(ComputeProductPurchasePolicyMapper mapper) { this.mapper = mapper; }

    public ComputeProductPurchasePolicyRepository(ComputeData data) {
        this(data.mapper(ComputeProductPurchasePolicyMapper.class));
    }

    public List<Map<String, Object>> quotaOrderRows(Object... args) {
        return ComputeData.normalizeRows(mapper.quotaOrderRows(args));
    }
}
