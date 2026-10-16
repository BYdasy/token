package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputePresentationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputePresentationService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputePresentationRepository {
    private final ComputePresentationMapper mapper;

    @Autowired
    public ComputePresentationRepository(ComputePresentationMapper mapper) { this.mapper = mapper; }

    public ComputePresentationRepository(ComputeData data) {
        this(data.mapper(ComputePresentationMapper.class));
    }

    public int saveSettingWrite(Object... args) {
        return mapper.saveSettingWrite(args);
    }

    public List<String> ratesCurrencyRows(Object... args) {
        return RepositoryResults.columnList(mapper.ratesCurrencyRows(args), String.class);
    }

    public Map<String, Object> memberMemberRow(Object... args) {
        return RepositoryResults.required(mapper.memberMemberRow(args));
    }

    public List<Map<String, Object>> memberWalletRows(Object... args) {
        return ComputeData.normalizeRows(mapper.memberWalletRows(args));
    }

    public List<Map<String, Object>> memberLevelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.memberLevelRows(args));
    }

    public Map<String, Object> memberAutoContractCount(Object... args) {
        return RepositoryResults.required(mapper.memberAutoContractCount(args));
    }

    public BigDecimal memberLedgerValue(Object... args) {
        return RepositoryResults.scalar(mapper.memberLedgerValue(args), BigDecimal.class);
    }

    public Map<String, Object> unreadContentRecipientCount(Object... args) {
        return RepositoryResults.required(mapper.unreadContentRecipientCount(args));
    }
}
