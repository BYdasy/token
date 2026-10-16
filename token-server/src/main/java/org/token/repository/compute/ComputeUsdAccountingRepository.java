package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeUsdAccountingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeUsdAccounting 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeUsdAccountingRepository {
    private final ComputeUsdAccountingMapper mapper;

    @Autowired
    public ComputeUsdAccountingRepository(ComputeUsdAccountingMapper mapper) { this.mapper = mapper; }

    public ComputeUsdAccountingRepository(ComputeData data) {
        this(data.mapper(ComputeUsdAccountingMapper.class));
    }

    public List<Map<String, Object>> strictQuoteCurrencyAssetRows(Object... args) {
        return ComputeData.normalizeRows(mapper.strictQuoteCurrencyAssetRows(args));
    }

    public List<Map<String, Object>> strictQuoteExchangeRateRows(Object... args) {
        return ComputeData.normalizeRows(mapper.strictQuoteExchangeRateRows(args));
    }

    public List<Map<String, Object>> legacySnapshots(String kind, Collection<Long> ids) {
        return ComputeData.normalizeRows(mapper.legacySnapshots(kind, ids));
    }
}
