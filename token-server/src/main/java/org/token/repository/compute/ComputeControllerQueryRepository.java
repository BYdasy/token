package org.token.repository.compute;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.token.repository.dao.compute.ComputeControllerQueryMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ComputeControllerQueryRepository {
    private final ComputeControllerQueryMapper mapper;

    public String activityType(long id) {
        String type = mapper.activityType(id);
        if (type == null) throw new org.token.exception.BusinessException("server.compute.notFound");
        return ComputeData.text(type);
    }

    public String participationType(long id) {
        String type = mapper.participationType(id);
        if (type == null) throw new org.token.exception.BusinessException("server.compute.notFound");
        return ComputeData.text(type);
    }

    public List<Map<String, Object>> memberAddresses(long memberId) {
        return ComputeData.normalizeRows(mapper.memberAddresses(memberId));
    }
}
