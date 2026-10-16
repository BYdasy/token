package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeExchangeProviderSettingsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeExchangeProviderSettings 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeExchangeProviderSettingsRepository {
    private final ComputeExchangeProviderSettingsMapper mapper;

    @Autowired
    public ComputeExchangeProviderSettingsRepository(ComputeExchangeProviderSettingsMapper mapper) { this.mapper = mapper; }

    public ComputeExchangeProviderSettingsRepository(ComputeData data) {
        this(data.mapper(ComputeExchangeProviderSettingsMapper.class));
    }

    public List<Map<String, Object>> lockConfigurationSettingRows(Object... args) {
        return RepositoryResults.rawRows(mapper.lockConfigurationSettingRows(args));
    }

    public int saveSettingWrite(Object... args) {
        return mapper.saveSettingWrite(args);
    }
}
