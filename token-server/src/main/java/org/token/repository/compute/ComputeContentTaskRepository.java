package org.token.repository.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import org.token.repository.support.*;
import org.token.repository.dao.compute.ComputeContentTaskMapper;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

@Repository
public class ComputeContentTaskRepository {
    private final ComputeContentTaskMapper mapper;
    @Autowired public ComputeContentTaskRepository(ComputeContentTaskMapper mapper) { this.mapper = mapper; }
    public ComputeContentTaskRepository(ComputeData data) { this(data.mapper(ComputeContentTaskMapper.class)); }

    public Long activeRestrictions(long memberId, LocalDateTime starts, LocalDateTime ends, Collection<Integer> modes) {
        return mapper.activeRestrictions(memberId, starts, ends, modes);
    }
}
