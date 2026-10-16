package org.token.repository.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import org.token.repository.support.*;
import org.token.repository.dao.compute.ComputeMcagAddressMapper;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

@Repository
public class ComputeMcagAddressRepository {
    private final ComputeMcagAddressMapper mapper;
    @Autowired public ComputeMcagAddressRepository(ComputeMcagAddressMapper mapper) { this.mapper = mapper; }
    public ComputeMcagAddressRepository(ComputeData data) { this(data.mapper(ComputeMcagAddressMapper.class)); }

    public List<Map<String, Object>> disabledIds(org.token.model.query.DepositAddressIdentity identity) {
        return ComputeData.normalizeRows(mapper.disabledIds(identity, identity.address().matches("(?i)0x[0-9a-f]{40}")));
    }

    public List<Map<String, Object>> disabledOrigin(org.token.model.query.DepositAddressIdentity identity) {
        return ComputeData.normalizeRows(mapper.disabledOrigin(identity, identity.address().matches("(?i)0x[0-9a-f]{40}")));
    }

    public List<Map<String, Object>> lockBindings(org.token.model.query.DepositAddressIdentity identity) {
        return ComputeData.normalizeRows(mapper.lockBindings(identity, identity.address().matches("(?i)0x[0-9a-f]{40}")));
    }

    public int disableBindings(org.token.model.query.DepositAddressIdentity identity, LocalDateTime time, int operator) {
        return mapper.disableBindings(identity, identity.address().matches("(?i)0x[0-9a-f]{40}"), time, operator);
    }
}
