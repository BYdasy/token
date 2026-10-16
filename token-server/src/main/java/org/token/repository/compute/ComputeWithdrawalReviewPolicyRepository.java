package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeWithdrawalReviewPolicyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeWithdrawalReviewPolicy 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeWithdrawalReviewPolicyRepository {
    private final ComputeWithdrawalReviewPolicyMapper mapper;

    @Autowired
    public ComputeWithdrawalReviewPolicyRepository(ComputeWithdrawalReviewPolicyMapper mapper) { this.mapper = mapper; }

    public ComputeWithdrawalReviewPolicyRepository(ComputeData data) {
        this(data.mapper(ComputeWithdrawalReviewPolicyMapper.class));
    }

    public int saveSettingWrite(Object... args) {
        return mapper.saveSettingWrite(args);
    }
}
