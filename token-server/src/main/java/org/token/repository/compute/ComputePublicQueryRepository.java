package org.token.repository.compute;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.token.repository.dao.compute.ComputePublicQueryMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ComputePublicQueryRepository {
    private final ComputePublicQueryMapper mapper;

    public List<Map<String, Object>> enabledLanguages() {
        return ComputeData.normalizeRows(mapper.enabledLanguages()).stream().map(row -> {
            Object language = row.get("lang");
            return Map.of("code", language, "name", row.get("name") == null ? language : row.get("name"),
                    "nativeName", row.get("nativeName") == null ? language : row.get("nativeName"));
        }).toList();
    }

}
