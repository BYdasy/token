package org.token.repository.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.IncorrectResultSetColumnCountException;
import org.springframework.util.LinkedCaseInsensitiveMap;

/** 保留旧接口的行数、空值、列名和金额类型契约，不包含业务查询。 */
public final class RepositoryResults {
    private RepositoryResults() { }

    public static Map<String, Object> required(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) throw new BusinessException("server.compute.notFound");
        return ComputeData.normalizeRow(rows.get(0));
    }

    public static List<Map<String, Object>> rawRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> converted = new LinkedCaseInsensitiveMap<>();
            if (row != null) converted.putAll(row);
            result.add(converted);
        }
        return result;
    }

    public static Map<String, Object> singleRow(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) throw new org.springframework.dao.EmptyResultDataAccessException(1);
        if (rows.size() != 1) throw new IncorrectResultSizeDataAccessException(1, rows.size());
        return rawRows(rows).get(0);
    }

    public static <T> T scalar(List<Map<String, Object>> rows, Class<T> type) {
        if (rows.isEmpty()) throw new org.springframework.dao.EmptyResultDataAccessException(1);
        if (rows.size() != 1) throw new IncorrectResultSizeDataAccessException(1, rows.size());
        return column(rows.get(0), type);
    }

    public static <T> List<T> columnList(List<Map<String, Object>> rows, Class<T> type) {
        List<T> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) result.add(column(row, type));
        return result;
    }

    private static <T> T column(Map<String, Object> row, Class<T> type) {
        if (row == null) return null;
        if (row.size() != 1) throw new IncorrectResultSetColumnCountException(1, row.size());
        Object value = row.values().iterator().next();
        if (value == null || type.isInstance(value)) return type.cast(value);
        if (value instanceof java.sql.Date date && type == java.time.LocalDate.class) return type.cast(date.toLocalDate());
        if (value instanceof java.sql.Time time && type == java.time.LocalTime.class) return type.cast(time.toLocalTime());
        if (value instanceof java.sql.Timestamp time && type == java.time.LocalDateTime.class) return type.cast(time.toLocalDateTime());
        return DefaultConversionService.getSharedInstance().convert(value, type);
    }
}
