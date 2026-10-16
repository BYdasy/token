package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeWithdrawalLimitPolicyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeWithdrawalLimitPolicy 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeWithdrawalLimitPolicyRepository {
    private final ComputeWithdrawalLimitPolicyMapper mapper;

    @Autowired
    public ComputeWithdrawalLimitPolicyRepository(ComputeWithdrawalLimitPolicyMapper mapper) { this.mapper = mapper; }

    public ComputeWithdrawalLimitPolicyRepository(ComputeData data) {
        this(data.mapper(ComputeWithdrawalLimitPolicyMapper.class));
    }

    public int saveSettingWrite(Object... args) {
        return mapper.saveSettingWrite(args);
    }

    public Long assertAllowedWithdrawalCount(Object... args) {
        return RepositoryResults.scalar(mapper.assertAllowedWithdrawalCount(args), Long.class);
    }
}
