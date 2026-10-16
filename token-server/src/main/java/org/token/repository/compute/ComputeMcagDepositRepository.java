package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeMcagDepositMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeMcagDepositService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeMcagDepositRepository {
    private final ComputeMcagDepositMapper mapper;

    public boolean registeredMemberExists(long memberId) {
        return mapper.registeredMemberCount(memberId) != 0;
    }

    @Autowired
    public ComputeMcagDepositRepository(ComputeMcagDepositMapper mapper) { this.mapper = mapper; }

    public ComputeMcagDepositRepository(ComputeData data) {
        this(data.mapper(ComputeMcagDepositMapper.class));
    }

    public Map<String, Object> bindMemberRow(Object... args) {
        return RepositoryResults.required(mapper.bindMemberRow(args));
    }

    public Map<String, Object> bindMcagAddressCurrentRow(Object... args) {
        return RepositoryResults.required(mapper.bindMcagAddressCurrentRow(args));
    }

    public int bindMcagAddressCurrentWrite(Object... args) {
        return mapper.bindMcagAddressCurrentWrite(args);
    }

    public Map<String, Object> bindMcagAddressCurrentRow2(Object... args) {
        return RepositoryResults.required(mapper.bindMcagAddressCurrentRow2(args));
    }

    public List<Map<String, Object>> rememberBindingMcagAddressRows(Object... args) {
        return ComputeData.normalizeRows(mapper.rememberBindingMcagAddressRows(args));
    }

    public List<Map<String, Object>> cachedMcagAddressCurrentRows(Object... args) {
        return ComputeData.normalizeRows(mapper.cachedMcagAddressCurrentRows(args));
    }

    public Map<String, Object> claimRefreshMemberRow(Object... args) {
        return RepositoryResults.required(mapper.claimRefreshMemberRow(args));
    }

    public int claimRefreshMcagAddressCurrentWrite(Object... args) {
        return mapper.claimRefreshMcagAddressCurrentWrite(args);
    }

    public int failRefreshMcagAddressCurrentWrite(Object... args) {
        return mapper.failRefreshMcagAddressCurrentWrite(args);
    }

    public List<Map<String, Object>> currentCustomerMcagAddressGenerationRows(Object... args) {
        return ComputeData.normalizeRows(mapper.currentCustomerMcagAddressGenerationRows(args));
    }

    public int prepareIdentityMcagAddressGenerationWrite(Object... args) {
        return mapper.prepareIdentityMcagAddressGenerationWrite(args);
    }

    public int prepareIdentityMcagCustomerIdentityWrite(Object... args) {
        return mapper.prepareIdentityMcagCustomerIdentityWrite(args);
    }

    public List<Map<String, Object>> customerIdentityMcagCustomerIdentityRows(Object... args) {
        return ComputeData.normalizeRows(mapper.customerIdentityMcagCustomerIdentityRows(args));
    }

    public int ensureCurrentMcagAddressCurrentWrite(Object... args) {
        return mapper.ensureCurrentMcagAddressCurrentWrite(args);
    }

    public List<Map<String, Object>> recordEventMcagEventRows(Object... args) {
        return ComputeData.normalizeRows(mapper.recordEventMcagEventRows(args));
    }

    public int recordEventMcagEventWrite(Object... args) {
        return mapper.recordEventMcagEventWrite(args);
    }

    public Map<String, Object> recordEventMcagEventRow(Object... args) {
        return RepositoryResults.required(mapper.recordEventMcagEventRow(args));
    }

    public int ensureCursorMcagCursorWrite(Object... args) {
        return mapper.ensureCursorMcagCursorWrite(args);
    }

    public Long cursorMcagCursorValue(Object... args) {
        return RepositoryResults.scalar(mapper.cursorMcagCursorValue(args), Long.class);
    }

    public int advanceMcagCursorWrite(Object... args) {
        return mapper.advanceMcagCursorWrite(args);
    }
}
