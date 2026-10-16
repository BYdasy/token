package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeDepositAccountingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeDepositAccounting 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeDepositAccountingRepository {
    private final ComputeDepositAccountingMapper mapper;

    @Autowired
    public ComputeDepositAccountingRepository(ComputeDepositAccountingMapper mapper) { this.mapper = mapper; }

    public ComputeDepositAccountingRepository(ComputeData data) {
        this(data.mapper(ComputeDepositAccountingMapper.class));
    }

    public int prepareDepositWrite(Object... args) {
        return mapper.prepareDepositWrite(args);
    }

    public int prepareDepositWrite2(Object... args) {
        return mapper.prepareDepositWrite2(args);
    }

    public int prepareDepositWrite3(Object... args) {
        return mapper.prepareDepositWrite3(args);
    }

    public Map<String, Object> prepareDepositRow(Object... args) {
        return RepositoryResults.required(mapper.prepareDepositRow(args));
    }

    public Map<String, Object> pendingReasonDepositRow(Object... args) {
        return RepositoryResults.required(mapper.pendingReasonDepositRow(args));
    }
}
