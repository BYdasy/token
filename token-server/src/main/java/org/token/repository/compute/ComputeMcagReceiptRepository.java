package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeMcagReceiptMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeMcagReceiptService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeMcagReceiptRepository {
    private final ComputeMcagReceiptMapper mapper;

    @Autowired
    public ComputeMcagReceiptRepository(ComputeMcagReceiptMapper mapper) { this.mapper = mapper; }

    public ComputeMcagReceiptRepository(ComputeData data) {
        this(data.mapper(ComputeMcagReceiptMapper.class));
    }

    public Map<String, Object> applyMemberRow(Object... args) {
        return RepositoryResults.required(mapper.applyMemberRow(args));
    }

    public Map<String, Object> applyMcagAddressRow(Object... args) {
        return RepositoryResults.required(mapper.applyMcagAddressRow(args));
    }

    public Map<String, Object> applyMcagCursorRow(Object... args) {
        return RepositoryResults.required(mapper.applyMcagCursorRow(args));
    }

    public List<Map<String, Object>> applyMcagDepositOrderRows(Object... args) {
        return ComputeData.normalizeRows(mapper.applyMcagDepositOrderRows(args));
    }

    public List<Map<String, Object>> applyMcagDepositOrderRows2(Object... args) {
        return ComputeData.normalizeRows(mapper.applyMcagDepositOrderRows2(args));
    }

    public Map<String, Object> applyDepositRow(Object... args) {
        return RepositoryResults.required(mapper.applyDepositRow(args));
    }

    public int applyDepositWrite(Object... args) {
        return mapper.applyDepositWrite(args);
    }

    public int applyDepositWrite2(Object... args) {
        return mapper.applyDepositWrite2(args);
    }

    public List<Map<String, Object>> bindingMcagAddressRows(Object... args) {
        return ComputeData.normalizeRows(mapper.bindingMcagAddressRows(args));
    }

    public List<Map<String, Object>> bindingMemberRows(Object... args) {
        return ComputeData.normalizeRows(mapper.bindingMemberRows(args));
    }

    public Map<String, Object> bindingMcagAddressRow(Object... args) {
        return RepositoryResults.required(mapper.bindingMcagAddressRow(args));
    }
}
