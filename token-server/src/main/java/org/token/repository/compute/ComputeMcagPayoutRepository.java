package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeMcagPayoutMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeMcagPayoutService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeMcagPayoutRepository {
    private final ComputeMcagPayoutMapper mapper;

    @Autowired
    public ComputeMcagPayoutRepository(ComputeMcagPayoutMapper mapper) { this.mapper = mapper; }

    public ComputeMcagPayoutRepository(ComputeData data) {
        this(data.mapper(ComputeMcagPayoutMapper.class));
    }

    public List<Map<String, Object>> claimMcagPayoutRows(Object... args) {
        return ComputeData.normalizeRows(mapper.claimMcagPayoutRows(args));
    }

    public int claimMcagPayoutWrite(Object... args) {
        return mapper.claimMcagPayoutWrite(args);
    }

    public Map<String, Object> executeWithdrawalRow(Object... args) {
        return RepositoryResults.required(mapper.executeWithdrawalRow(args));
    }

    public Map<String, Object> executeMemberRow(Object... args) {
        return RepositoryResults.required(mapper.executeMemberRow(args));
    }

    public int executeMcagPayoutWrite(Object... args) {
        return mapper.executeMcagPayoutWrite(args);
    }

    public int executeMcagPayoutWrite2(Object... args) {
        return mapper.executeMcagPayoutWrite2(args);
    }

    public int executeMcagPayoutWrite3(Object... args) {
        return mapper.executeMcagPayoutWrite3(args);
    }

    public int executeMcagPayoutWrite4(Object... args) {
        return mapper.executeMcagPayoutWrite4(args);
    }

    public int reconcileMcagPayoutWrite(Object... args) {
        return mapper.reconcileMcagPayoutWrite(args);
    }

    public Map<String, Object> cancelUnsubmittedMcagPayoutRow(Object... args) {
        return RepositoryResults.required(mapper.cancelUnsubmittedMcagPayoutRow(args));
    }

    public int cancelUnsubmittedMcagPayoutWrite(Object... args) {
        return mapper.cancelUnsubmittedMcagPayoutWrite(args);
    }

    public Map<String, Object> cancelUnsubmittedMemberRow(Object... args) {
        return RepositoryResults.required(mapper.cancelUnsubmittedMemberRow(args));
    }

    public Map<String, Object> cancelUnsubmittedWithdrawalRow(Object... args) {
        return RepositoryResults.required(mapper.cancelUnsubmittedWithdrawalRow(args));
    }

    public int cancelUnsubmittedWithdrawalWrite(Object... args) {
        return mapper.cancelUnsubmittedWithdrawalWrite(args);
    }

    public int cancelUnsubmittedMcagPayoutWrite2(Object... args) {
        return mapper.cancelUnsubmittedMcagPayoutWrite2(args);
    }

    public List<Map<String, Object>> applyMcagPayoutRows(Object... args) {
        return ComputeData.normalizeRows(mapper.applyMcagPayoutRows(args));
    }

    public Map<String, Object> applyWithdrawalRow(Object... args) {
        return RepositoryResults.required(mapper.applyWithdrawalRow(args));
    }

    public int applyMcagPayoutWrite(Object... args) {
        return mapper.applyMcagPayoutWrite(args);
    }

    public int applyWithdrawalWrite(Object... args) {
        return mapper.applyWithdrawalWrite(args);
    }

    public int applyWithdrawalWrite2(Object... args) {
        return mapper.applyWithdrawalWrite2(args);
    }

    public int reviewMcagPayoutWrite(Object... args) {
        return mapper.reviewMcagPayoutWrite(args);
    }

    public int reviewWithdrawalWrite(Object... args) {
        return mapper.reviewWithdrawalWrite(args);
    }

    public int waitForFundsMcagPayoutWrite(Object... args) {
        return mapper.waitForFundsMcagPayoutWrite(args);
    }

    public int waitForFundsWithdrawalWrite(Object... args) {
        return mapper.waitForFundsWithdrawalWrite(args);
    }

    public int retryMcagPayoutWrite(Object... args) {
        return mapper.retryMcagPayoutWrite(args);
    }

    public int releaseMcagPayoutWrite(Object... args) {
        return mapper.releaseMcagPayoutWrite(args);
    }

    public Map<String, Object> localMcagPayoutRow(Object... args) {
        return RepositoryResults.required(mapper.localMcagPayoutRow(args));
    }
}
