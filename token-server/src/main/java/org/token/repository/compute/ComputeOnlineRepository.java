package org.token.repository.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import org.token.repository.support.*;
import org.token.repository.dao.compute.ComputeOnlineMapper;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

@Repository
public class ComputeOnlineRepository {
    private final ComputeOnlineMapper mapper;
    @Autowired public ComputeOnlineRepository(ComputeOnlineMapper mapper) { this.mapper = mapper; }
    public ComputeOnlineRepository(ComputeData data) { this(data.mapper(ComputeOnlineMapper.class)); }

    public List<Long> onlineMemberIds(Collection<Long> members, LocalDateTime observedAt, LocalDateTime onlineSince, LocalDateTime idleSince) {
        return mapper.onlineMemberIds(members, observedAt, onlineSince, idleSince);
    }

    public static String predicate(LocalDateTime observedAt, LocalDateTime onlineSince, LocalDateTime idleSince, List<Object> args) {
        args.add(observedAt); args.add(onlineSince); args.add(idleSince);
        return "s.expires_at>? AND s.last_seen_at>=? AND s.last_seen_at>? AND s.credential_hash=m.password_hash AND m.enabled=1";
    }
}
