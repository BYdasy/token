package org.token.service.compute;

import org.token.repository.compute.ComputeVipModelAllocationRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.token.repository.compute.ComputeData.*;

/** VIP 仅绑定供应商；每次新执行固定当时启用的候选模型。 */
final class ComputeVipModelAllocation {
    private ComputeVipModelAllocation() { }

    static List<Long> providerIds(ComputeData data, Map<String, Object> level) {
        return level.get("autoProviderIds") == null ? List.of() : normalizedIds(level.get("autoProviderIds"));
    }

    static List<Long> normalizedIds(Object value) {
        if (!(value instanceof Collection<?> ids) || ids.size() > 100) throw new BusinessException("server.compute.invalidInput");
        try {
            return ids.stream().map(id -> new BigDecimal(text(id)).longValueExact()).peek(id -> {
                if (id <= 0) throw new BusinessException("server.compute.invalidInput");
            }).distinct().sorted().toList();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new BusinessException("server.compute.invalidInput");
        }
    }

    static List<Map<String, Object>> models(ComputeData data, Map<String, Object> level) {
        List<Long> providers = providerIds(data, level);
        if (providers.isEmpty()) return List.of();
        return new ComputeVipModelAllocationRepository(data).enabledModels(providers);
    }

    private static String placeholders(int size) { return String.join(",", java.util.Collections.nCopies(size, "?")); }
}
