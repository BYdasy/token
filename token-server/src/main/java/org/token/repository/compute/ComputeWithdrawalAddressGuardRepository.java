package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeWithdrawalAddressGuardMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeWithdrawalAddressGuard 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeWithdrawalAddressGuardRepository {
    private final ComputeWithdrawalAddressGuardMapper mapper;

    @Autowired
    public ComputeWithdrawalAddressGuardRepository(ComputeWithdrawalAddressGuardMapper mapper) { this.mapper = mapper; }

    public ComputeWithdrawalAddressGuardRepository(ComputeData data) {
        this(data.mapper(ComputeWithdrawalAddressGuardMapper.class));
    }

    public int assertAllowedAddressGuardWrite(Object... args) {
        return mapper.assertAllowedAddressGuardWrite(args);
    }

    public List<Map<String, Object>> assertAllowedAddressGuardRows(Object... args) {
        return RepositoryResults.rawRows(mapper.assertAllowedAddressGuardRows(args));
    }

    public List<Map<String, Object>> assertAllowedAddressRows(Object... args) {
        return ComputeData.normalizeRows(mapper.assertAllowedAddressRows(args));
    }

    public List<Map<String, Object>> assertAllowedMcagAddressRows(Object... args) {
        return ComputeData.normalizeRows(mapper.assertAllowedMcagAddressRows(args));
    }

    public List<Map<String, Object>> assertRemovableWithdrawalRows(Object... args) {
        return ComputeData.normalizeRows(mapper.assertRemovableWithdrawalRows(args));
    }

    public Map<String, Object> assertRemovableCurrencyNetworkRow(Object... args) {
        return RepositoryResults.required(mapper.assertRemovableCurrencyNetworkRow(args));
    }
}
