package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputePaymentRecordsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputePaymentRecords 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputePaymentRecordsRepository {
    private final ComputePaymentRecordsMapper mapper;

    @Autowired
    public ComputePaymentRecordsRepository(ComputePaymentRecordsMapper mapper) { this.mapper = mapper; }

    public ComputePaymentRecordsRepository(ComputeData data) {
        this(data.mapper(ComputePaymentRecordsMapper.class));
    }

    public List<Map<String, Object>> enrichCurrencyNetworkRows(Object... args) {
        return ComputeData.normalizeRows(mapper.enrichCurrencyNetworkRows(args));
    }

    public List<Map<String, Object>> memberNames(Collection<?> ids) {
        return ComputeData.normalizeRows(mapper.memberNames(ids));
    }

    public List<Map<String, Object>> payoutReferences(Collection<?> ids) {
        return ComputeData.normalizeRows(mapper.payoutReferences(ids));
    }

    public List<Map<String, Object>> orderReferences(Collection<?> ids) {
        return ComputeData.normalizeRows(mapper.orderReferences(ids));
    }

    public List<Map<String, Object>> orderCollectionBatches(Collection<? extends Map<String, Object>> links) {
        return links.isEmpty() ? List.of() : ComputeData.normalizeRows(mapper.orderCollectionBatches(links));
    }
}
