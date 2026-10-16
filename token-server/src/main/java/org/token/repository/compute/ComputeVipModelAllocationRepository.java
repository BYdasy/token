package org.token.repository.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import org.token.repository.support.*;
import org.token.repository.dao.compute.ComputeVipModelAllocationMapper;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

@Repository
public class ComputeVipModelAllocationRepository {
    private final ComputeVipModelAllocationMapper mapper;
    @Autowired public ComputeVipModelAllocationRepository(ComputeVipModelAllocationMapper mapper) { this.mapper = mapper; }
    public ComputeVipModelAllocationRepository(ComputeData data) { this(data.mapper(ComputeVipModelAllocationMapper.class)); }

    public List<Map<String, Object>> enabledModels(Collection<Long> ids) {
        return ComputeData.normalizeRows(mapper.enabledModels(ids));
    }
}
