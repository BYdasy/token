package org.token.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 只存当前后台账号的栏位键，不保存业务记录、SQL、权限或其他账号的偏好。 */
@Service
public class TableColumnPreferenceService {
    private static final TypeReference<List<String>> COLUMN_KEYS = new TypeReference<>() { };
    private final org.token.repository.system.TableColumnPreferenceRepository repository;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    public TableColumnPreferenceService(org.token.repository.system.TableColumnPreferenceRepository repository,ObjectMapper mapper) { this.repository=repository;this.mapper=mapper; }
    public TableColumnPreferenceService(JdbcTemplate jdbc,ObjectMapper mapper) { this(new org.token.repository.system.TableColumnPreferenceRepository(jdbc),mapper); }

    @Transactional(readOnly = true)
    public Map<String, Object> read(int uid, String pageKey) {
        validatePageKey(pageKey);
        var records = repository.read(uid,pageKey);
        List<String> columns = null;
        if (!records.isEmpty()) {
            try {
                columns = validateColumns(mapper.readValue(records.get(0), COLUMN_KEYS));
            } catch (Exception exception) {
                throw new BusinessException("server.tableColumns.invalidStoredValue");
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("columns", columns);
        return result;
    }

    @Transactional
    public Map<String, Object> save(int uid, String pageKey, List<String> columns) {
        validatePageKey(pageKey);
        List<String> normalized = validateColumns(columns);
        String json;
        try {
            json = mapper.writeValueAsString(normalized);
        } catch (Exception exception) {
            throw invalid();
        }
        repository.save(uid,pageKey,json,java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        return Map.of("columns", normalized);
    }

    private static void validatePageKey(String pageKey) {
        if (pageKey == null || !pageKey.matches("[A-Za-z0-9][A-Za-z0-9_:./-]{0,99}")) throw invalid();
    }

    private static List<String> validateColumns(List<String> columns) {
        if (columns == null || columns.size() > 160) throw invalid();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String column : columns) {
            if (column == null || !column.matches("[A-Za-z0-9_][A-Za-z0-9_.:-]{0,99}")) throw invalid();
            normalized.add(column);
        }
        return List.copyOf(normalized);
    }

    private static BusinessException invalid() {
        return new BusinessException("server.tableColumns.invalidInput");
    }
}
