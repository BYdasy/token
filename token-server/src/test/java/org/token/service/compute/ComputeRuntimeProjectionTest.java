package org.token.service.compute;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.token.repository.compute.ComputeData.*;

/** 仅验证时间窗口一致性和容量边界，不启动服务或访问外部监控。 */
class ComputeRuntimeProjectionTest {
    @Test void automaticValuesShareTwoSecondFramesAndNeverExceedCapacity() {
        var config = ComputeRuntimeProjection.defaults();
        config.put("enabled", true);
        Instant start = Instant.parse("2026-09-17T00:00:00Z");
        var first = ComputeRuntimeProjection.project(config, start);
        assertEquals(first, ComputeRuntimeProjection.project(config, start));
        assertEquals(first.get("samples"), ComputeRuntimeProjection.project(config, start.plusMillis(1999)).get("samples"));
        assertEquals(start.toString(), first.get("serverTime"));
        assertEquals(start.toString(), first.get("updatedAt"));
        assertEquals(start.plusSeconds(2).toString(), first.get("nextUpdateAt"));
        for (int minute = 0; minute < 120; minute++) {
            for (var row : samples(ComputeRuntimeProjection.project(config, start.plusSeconds(minute * 60L)))) {
                assertTrue(number(row.get("activeNodes")) <= number(config.get("totalNodes")));
                assertTrue(number(row.get("runningPools")) <= number(config.get("totalPools")));
                assertTrue(number(row.get("runningPools")) <= number(row.get("activeNodes")));
                assertTrue(decimal(row.get("consumed")).compareTo(decimal(config.get("capacity"))) <= 0);
                assertTrue(decimal(row.get("utilizationPercent")).compareTo(decimal(config.get("usageMinPercent"))) >= 0);
                assertTrue(decimal(row.get("utilizationPercent")).compareTo(decimal(config.get("usageMaxPercent"))) <= 0);
            }
        }
        config.put("capacity", "0.5");
        config.put("usageMinPercent", 100);
        config.put("usageMaxPercent", 100);
        assertEquals(0, new BigDecimal("0.5").compareTo(decimal(ComputeRuntimeProjection.project(config, start).get("consumed"))));
        config.put("totalNodes", 0);
        var empty = ComputeRuntimeProjection.project(config, start);
        assertEquals(0, decimal(empty.get("consumed")).signum());
        assertEquals(0, number(empty.get("runningPools")));
    }
    @Test void samplesChangeVisiblyAndAgreeWithLaterRequests() {
        var config = ComputeRuntimeProjection.defaults();
        config.put("enabled", true);
        Instant start = Instant.parse("2026-10-17T12:00:00Z");
        var rows = samples(ComputeRuntimeProjection.project(config, start));
        int visiblyChanged = 0;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var current = ComputeRuntimeProjection.project(config, Instant.parse(row.get("at").toString()));
            for (String key : List.of("activeNodes", "runningPools", "consumed", "utilizationPercent")) assertEquals(row.get(key), current.get(key));
            if (index > 0 && decimal(row.get("utilizationPercent")).subtract(decimal(rows.get(index - 1).get("utilizationPercent"))).abs().compareTo(new BigDecimal("0.1")) >= 0) visiblyChanged++;
        }
        assertTrue(visiblyChanged >= (rows.size() - 1) * .9, "大多数相邻2秒帧应有可见的比例变化");
        assertTrue(rows.stream().map(row -> row.get("activeNodes")).distinct().count() > 3);
    }
    @Test void samplesCoverRefreshWindowAndNetworkMarginWithBoundedSize() {
        var config = ComputeRuntimeProjection.defaults();
        config.put("enabled", true);
        Instant current = Instant.parse("2026-10-17T12:00:01.999Z");
        for (int refresh : List.of(10, 11, 60, 299, 300)) {
            config.put("refreshSeconds", refresh);
            var projected = ComputeRuntimeProjection.project(config, current);
            var rows = samples(projected);
            assertEquals(2, number(projected.get("sampleSeconds")));
            assertEquals(1 + (refresh + 11) / 2, rows.size());
            assertTrue(rows.size() <= 156);
            Instant first = Instant.parse(rows.get(0).get("at").toString());
            Instant last = Instant.parse(rows.get(rows.size() - 1).get("at").toString());
            assertEquals(Instant.parse("2026-10-17T12:00:00Z"), first);
            assertTrue(Duration.between(first, last).getSeconds() >= refresh + 10);
            for (int index = 1; index < rows.size(); index++) {
                assertEquals(2, Duration.between(Instant.parse(rows.get(index - 1).get("at").toString()), Instant.parse(rows.get(index).get("at").toString())).getSeconds());
            }
        }
        // 读取旧配置中的异常窗口也不能产生无界响应。
        config.put("refreshSeconds", Long.MAX_VALUE);
        assertEquals(156, samples(ComputeRuntimeProjection.project(config, current)).size());
        config.put("refreshSeconds", 0);
        assertEquals(11, samples(ComputeRuntimeProjection.project(config, current)).size());
    }
    @Test void zeroCapacityNodesOrPoolsCannotCreateUsage() {
        for (String zero : List.of("capacity", "totalNodes", "totalPools")) {
            var config = ComputeRuntimeProjection.defaults();
            config.put("enabled", true); config.put(zero, 0);
            for (var row : samples(ComputeRuntimeProjection.project(config, Instant.parse("2026-10-17T12:00:00Z")))) {
                assertEquals(0, decimal(row.get("consumed")).signum());
                assertEquals(0, decimal(row.get("utilizationPercent")).signum());
                if (!"capacity".equals(zero)) assertEquals(0, number(row.get("runningPools")));
                if ("totalNodes".equals(zero)) assertEquals(0, number(row.get("activeNodes")));
            }
        }
        var config = ComputeRuntimeProjection.defaults();
        config.put("enabled", true); config.put("totalNodes", 1); config.put("totalPools", 100);
        for (var row : samples(ComputeRuntimeProjection.project(config, Instant.parse("2026-10-17T12:00:00Z")))) {
            assertTrue(number(row.get("runningPools")) <= number(row.get("activeNodes")));
        }
    }
    @Test void manualValuesRemainUnchangedAcrossTimeWindows() {
        var config = ComputeRuntimeProjection.defaults();
        config.put("mode", "MANUAL"); config.put("enabled", true);
        var row = ComputeRuntimeProjection.project(config, Instant.parse("2026-09-17T14:23:44Z"));
        assertEquals(config.get("activeNodes"), row.get("activeNodes"));
        assertEquals(config.get("consumed"), row.get("consumed"));
        assertTrue(samples(row).isEmpty());
        var later = ComputeRuntimeProjection.project(config, Instant.parse("2026-09-17T14:24:44Z"));
        for (String key : List.of("activeNodes", "runningPools", "consumed", "utilizationPercent")) assertEquals(row.get(key), later.get(key));
        config.put("enabled", false); config.put("mode", "AUTO");
        var disabled = ComputeRuntimeProjection.project(config, Instant.parse("2026-09-17T14:24:44Z"));
        assertTrue(samples(disabled).isEmpty());
        assertEquals(config.get("activeNodes"), disabled.get("activeNodes"));
        assertEquals(config.get("consumed"), disabled.get("consumed"));
    }
    @Test void projectionDoesNotMutateConfigurationAndSyncWindowBoundsStayUnchanged() {
        var config = ComputeRuntimeProjection.defaults();
        config.put("enabled", true);
        var before = Map.copyOf(config);
        ComputeRuntimeProjection.project(config, Instant.parse("2026-10-17T12:00:00Z"));
        assertEquals(before, config);
        for (int refresh : List.of(2, 9, 301)) {
            config.put("refreshSeconds", refresh);
            assertThrows(org.token.exception.BusinessException.class, () -> ComputeRuntimeProjection.validate(new java.util.LinkedHashMap<>(), config));
        }
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> samples(Map<String,Object> projected) { return (List<Map<String,Object>>) projected.get("samples"); }
}
