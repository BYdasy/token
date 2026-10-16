package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeTokenAmountsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeTokenAmounts 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeTokenAmountsRepository {
    private final ComputeTokenAmountsMapper mapper;

    @Autowired
    public ComputeTokenAmountsRepository(ComputeTokenAmountsMapper mapper) { this.mapper = mapper; }

    public ComputeTokenAmountsRepository(ComputeData data) {
        this(data.mapper(ComputeTokenAmountsMapper.class));
    }

    public List<Map<String, Object>> rateCurrencyAssetRows(Object... args) {
        return ComputeData.normalizeRows(mapper.rateCurrencyAssetRows(args));
    }

    public List<Map<String, Object>> rateExchangeRateRows(Object... args) {
        return ComputeData.normalizeRows(mapper.rateExchangeRateRows(args));
    }
}
