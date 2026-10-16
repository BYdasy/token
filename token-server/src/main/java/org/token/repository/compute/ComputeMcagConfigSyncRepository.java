package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeMcagConfigSyncMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeMcagConfigSyncService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeMcagConfigSyncRepository {
    private final ComputeMcagConfigSyncMapper mapper;

    @Autowired
    public ComputeMcagConfigSyncRepository(ComputeMcagConfigSyncMapper mapper) { this.mapper = mapper; }

    public ComputeMcagConfigSyncRepository(ComputeData data) {
        this(data.mapper(ComputeMcagConfigSyncMapper.class));
    }

    public List<Map<String, Object>> statusMerchantSnapshotRows(Object... args) {
        return ComputeData.normalizeRows(mapper.statusMerchantSnapshotRows(args));
    }

    public List<Map<String, Object>> statusMcagChannelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.statusMcagChannelRows(args));
    }

    public int synchronizeMerchantSnapshotWrite(Object... args) {
        return mapper.synchronizeMerchantSnapshotWrite(args);
    }

    public Map<String, Object> synchronizeMerchantSnapshotRow(Object... args) {
        return RepositoryResults.required(mapper.synchronizeMerchantSnapshotRow(args));
    }

    public List<Map<String, Object>> synchronizeSettingRows(Object... args) {
        return RepositoryResults.rawRows(mapper.synchronizeSettingRows(args));
    }

    public Map<String, Object> synchronizeMerchantSnapshotRow2(Object... args) {
        return RepositoryResults.required(mapper.synchronizeMerchantSnapshotRow2(args));
    }

    public int synchronizeMerchantSnapshotWrite2(Object... args) {
        return mapper.synchronizeMerchantSnapshotWrite2(args);
    }

    public int synchronizeMerchantSnapshotWrite3(Object... args) {
        return mapper.synchronizeMerchantSnapshotWrite3(args);
    }

    public List<Map<String, Object>> applyMcagChannelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.applyMcagChannelRows(args));
    }

    public List<Map<String, Object>> applyCurrencyAssetRows(Object... args) {
        return ComputeData.normalizeRows(mapper.applyCurrencyAssetRows(args));
    }

    public List<Map<String, Object>> applyCurrencyNetworkRows(Object... args) {
        return ComputeData.normalizeRows(mapper.applyCurrencyNetworkRows(args));
    }

    public List<Map<String, Object>> applyCurrencyRows(Object... args) {
        return ComputeData.normalizeRows(mapper.applyCurrencyRows(args));
    }

    public int applyCurrencyNetworkWrite(Object... args) {
        return mapper.applyCurrencyNetworkWrite(args);
    }

    public int applyCurrencyWrite(Object... args) {
        return mapper.applyCurrencyWrite(args);
    }

    public int applyMcagChannelWrite(Object... args) {
        return mapper.applyMcagChannelWrite(args);
    }

    public int applyMcagChannelWrite2(Object... args) {
        return mapper.applyMcagChannelWrite2(args);
    }
}
