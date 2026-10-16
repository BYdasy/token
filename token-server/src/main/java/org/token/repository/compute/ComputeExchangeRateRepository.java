package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeExchangeRateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeExchangeRateService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeExchangeRateRepository {
    private final ComputeExchangeRateMapper mapper;

    @Autowired
    public ComputeExchangeRateRepository(ComputeExchangeRateMapper mapper) { this.mapper = mapper; }

    public ComputeExchangeRateRepository(ComputeData data) {
        this(data.mapper(ComputeExchangeRateMapper.class));
    }

    public List<Map<String, Object>> refreshProviderExchangeRefreshRows(Object... args) {
        return ComputeData.normalizeRows(mapper.refreshProviderExchangeRefreshRows(args));
    }

    public int refreshProviderExchangeRefreshWrite(Object... args) {
        return mapper.refreshProviderExchangeRefreshWrite(args);
    }

    public int refreshProviderExchangeRefreshWrite2(Object... args) {
        return mapper.refreshProviderExchangeRefreshWrite2(args);
    }

    public int finishExchangeRefreshWrite(Object... args) {
        return mapper.finishExchangeRefreshWrite(args);
    }

    public List<Map<String, Object>> enabledCryptoCurrencyAssetRows(Object... args) {
        return ComputeData.normalizeRows(mapper.enabledCryptoCurrencyAssetRows(args));
    }

    public int persistExchangeRateWrite(Object... args) {
        return mapper.persistExchangeRateWrite(args);
    }

    public List<Map<String, Object>> valuationExchangeRateRows(Object... args) {
        return ComputeData.normalizeRows(mapper.valuationExchangeRateRows(args));
    }

    public List<Map<String, Object>> valuationCurrencyAssetRows(Object... args) {
        return ComputeData.normalizeRows(mapper.valuationCurrencyAssetRows(args));
    }

    public List<Map<String, Object>> statusExchangeRefreshRows(Object... args) {
        return ComputeData.normalizeRows(mapper.statusExchangeRefreshRows(args));
    }

    public int claimProvider(String provider, boolean force, LocalDateTime attempt, LocalDateTime nextAttempt,
            LocalDateTime forceBefore,LocalDateTime automaticBefore,LocalDateTime expiredLeaseBefore) {
        return mapper.claimProvider(provider, force, attempt, nextAttempt, forceBefore,automaticBefore,expiredLeaseBefore);
    }
}
