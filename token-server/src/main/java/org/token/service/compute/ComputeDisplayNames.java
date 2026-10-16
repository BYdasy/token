package org.token.service.compute;

import org.token.repository.compute.ComputeDisplayNameRepository;

import org.token.repository.compute.ComputeData;

import java.util.*;

/** 仅补充已授权响应中的显示名称；按批查询，缺失账号不伪造用户名。 */
public final class ComputeDisplayNames {
    private ComputeDisplayNames() {}

    public static void administrators(ComputeData data, Collection<Map<String, Object>> rows, Map<String, String> fields) {
        resolve(data, rows, fields, true);
    }

    /** 调用前必须完成成员行与关联目标的数据范围裁剪，不能用此方法解析不可见祖先。 */
    public static void members(ComputeData data, Collection<Map<String, Object>> rows, Map<String, String> fields) {
        resolve(data, rows, fields, false);
    }

    private static void resolve(ComputeData data, Collection<Map<String, Object>> rows, Map<String, String> fields,
                                boolean administrators) {
        Set<Long> ids = new LinkedHashSet<>();
        for (var row : rows) for (String source : fields.keySet()) {
            Long id = id(row.get(source));
            if (id != null) ids.add(id);
        }
        List<Long> ordered = new ArrayList<>(ids);
        Map<Long, String> names = new HashMap<>();
        for (int start = 0; start < ordered.size(); start += 500) {
            List<Long> batch = ordered.subList(start, Math.min(start + 500, ordered.size()));
            for (var account : (administrators ? new ComputeDisplayNameRepository(data).administratorNames(batch)
                    : new ComputeDisplayNameRepository(data).memberNames(batch))) {
                names.put(id(account.get("displayId")), (String) account.get("username"));
            }
        }
        for (var row : rows) fields.forEach((source, target) -> row.put(target, names.get(id(row.get(source)))));
    }

    private static Long id(Object value) {
        if (value == null) return null;
        try { long id = Long.parseLong(value.toString()); return id > 0 ? id : null; }
        catch (NumberFormatException ignored) { return null; }
    }
}
