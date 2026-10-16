package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeMcagChannelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeMcagChannelService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeMcagChannelRepository {
    private final ComputeMcagChannelMapper mapper;

    @Autowired
    public ComputeMcagChannelRepository(ComputeMcagChannelMapper mapper) { this.mapper = mapper; }

    public ComputeMcagChannelRepository(ComputeData data) {
        this(data.mapper(ComputeMcagChannelMapper.class));
    }

    public List<Map<String, Object>> findChannelMcagChannelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.findChannelMcagChannelRows(args));
    }

    public List<Map<String, Object>> findByAssetMcagChannelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.findByAssetMcagChannelRows(args));
    }

    public List<Map<String, Object>> listSyncedChannelsMcagChannelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.listSyncedChannelsMcagChannelRows(args));
    }
}
