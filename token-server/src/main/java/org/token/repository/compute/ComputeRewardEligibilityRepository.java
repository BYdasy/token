package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeRewardEligibilityMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeRewardEligibility 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeRewardEligibilityRepository {
    private final ComputeRewardEligibilityMapper mapper;

    @Autowired
    public ComputeRewardEligibilityRepository(ComputeRewardEligibilityMapper mapper) { this.mapper = mapper; }

    public ComputeRewardEligibilityRepository(ComputeData data) {
        this(data.mapper(ComputeRewardEligibilityMapper.class));
    }

    public Map<String, Object> lockMembersMemberRow(Object... args) {
        return RepositoryResults.required(mapper.lockMembersMemberRow(args));
    }

    public List<Map<String, Object>> balanceWalletRows(Object... args) {
        return ComputeData.normalizeRows(mapper.balanceWalletRows(args));
    }

    public BigDecimal successfulDepositTokenDepositValue(Object... args) {
        return RepositoryResults.scalar(mapper.successfulDepositTokenDepositValue(args), BigDecimal.class);
    }
}
