package org.token.service.compute;

import org.junit.jupiter.api.Test;
import org.token.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.token.repository.compute.ComputeData.*;

/** 仅验证时间窗口一致性和容量边界，不启动服务或访问外部监控。 */
class ComputeRuntimeProjectionTest {
    @Test void automaticValuesShareTwoSecondFramesAndStayInsideConfiguredRanges() {
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
                assertTrue(decimal(row.get("exaflops")).compareTo(new BigDecimal("4.15")) >= 0);
                assertTrue(decimal(row.get("exaflops")).compareTo(new BigDecimal("4.50")) <= 0);
                assertTrue(number(row.get("onlineGpus")) >= 10900);
                assertTrue(number(row.get("onlineGpus")) <= 20000);
            }
        }
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
            for (String key : List.of("exaflops", "onlineGpus")) assertEquals(row.get(key), current.get(key));
            if (index > 0 && !row.get("exaflops").equals(rows.get(index - 1).get("exaflops"))) visiblyChanged++;
        }
        assertTrue(visiblyChanged >= (rows.size() - 1) * .9, "大多数相邻2秒帧应有可见的算力变化");
        assertTrue(rows.stream().map(row -> row.get("onlineGpus")).distinct().count() > 20);
    }

    @Test void samplesCoverIndependentRefreshWindowAtEverySupportedFrequency() {
        var config = ComputeRuntimeProjection.defaults();
        config.put("enabled", true);
        Instant current = Instant.parse("2026-10-17T12:00:01.999Z");
        for (int interval : List.of(1, 2, 5, 16, 60, 300)) {
            config.put("changeSeconds", interval);
            for (int refresh : List.of(10, 11, 60, 299, 300)) {
                config.put("refreshSeconds", refresh);
                var projected = ComputeRuntimeProjection.project(config, current);
                var rows = samples(projected);
                assertEquals(interval, number(projected.get("sampleSeconds")));
                assertEquals(1 + (refresh + 10 + interval - 1) / interval, rows.size());
                assertTrue(rows.size() <= 311);
                Instant first = Instant.parse(rows.get(0).get("at").toString());
                Instant last = Instant.parse(rows.get(rows.size() - 1).get("at").toString());
                assertTrue(Duration.between(first, last).getSeconds() >= refresh + 10);
                assertTrue(!first.isAfter(current) && Duration.between(first, current).getSeconds() < interval);
                for (int index = 1; index < rows.size(); index++) {
                    var row = rows.get(index);
                    assertEquals(interval, Duration.between(Instant.parse(rows.get(index - 1).get("at").toString()), Instant.parse(row.get("at").toString())).getSeconds());
                    var later = ComputeRuntimeProjection.project(config, Instant.parse(row.get("at").toString()));
                    assertEquals(row.get("exaflops"), later.get("exaflops"));
                    assertEquals(row.get("onlineGpus"), later.get("onlineGpus"));
                }
            }
        }
        // 读取异常配置也不能产生无界响应或零秒除法。
        config.put("refreshSeconds", Long.MAX_VALUE); config.put("changeSeconds", 0);
        assertEquals(311, samples(ComputeRuntimeProjection.project(config, current)).size());
        config.put("refreshSeconds", 0); config.put("changeSeconds", Long.MAX_VALUE);
        assertEquals(2, samples(ComputeRuntimeProjection.project(config, current)).size());
    }

    @Test void equalAndZeroRangesRemainExact() {
        var config = ComputeRuntimeProjection.defaults();
        config.put("enabled", true);
        for (String value : List.of("0", "0.05", "5.0")) {
            config.put("totalExaflops", value); config.put("exaflopsMin", value); config.put("exaflopsMax", value);
            config.put("onlineGpusMin", 0); config.put("onlineGpusMax", 0);
            for (var row : samples(ComputeRuntimeProjection.project(config, Instant.parse("2026-10-17T12:00:00Z")))) {
                assertEquals(0, new BigDecimal(value).compareTo(decimal(row.get("exaflops"))));
                assertEquals(0, number(row.get("onlineGpus")));
            }
        }
    }

    @Test void manualAndDisabledValuesRemainUnchangedAcrossTimeWindows() {
        var config = ComputeRuntimeProjection.defaults();
        config.put("mode", "MANUAL"); config.put("enabled", true);
        var row = ComputeRuntimeProjection.project(config, Instant.parse("2026-09-17T14:23:44Z"));
        assertEquals(config.get("exaflops"), row.get("exaflops"));
        assertEquals(config.get("onlineGpus"), row.get("onlineGpus"));
        assertTrue(samples(row).isEmpty());
        var later = ComputeRuntimeProjection.project(config, Instant.parse("2026-09-17T14:24:44Z"));
        for (String key : List.of("exaflops", "onlineGpus")) assertEquals(row.get(key), later.get(key));
        config.put("enabled", false); config.put("mode", "AUTO");
        var disabled = ComputeRuntimeProjection.project(config, Instant.parse("2026-09-17T14:24:44Z"));
        assertTrue(samples(disabled).isEmpty());
        assertEquals(config.get("exaflops"), disabled.get("exaflops"));
        assertEquals(config.get("onlineGpus"), disabled.get("onlineGpus"));
    }

    @Test void changeFrequencyAndConfigurationSyncRejectFractionalOrOutOfBoundsValues() {
        for (String key : List.of("changeSeconds", "refreshSeconds")) {
            for (Object value : List.of(0, 301, -1, "2.5", "invalid", "1000000000001")) {
                var config = ComputeRuntimeProjection.defaults(); config.put(key, value);
                assertThrows(BusinessException.class, () -> ComputeRuntimeProjection.validate(new LinkedHashMap<>(), config), key + ":" + value);
            }
        }
        var config = ComputeRuntimeProjection.defaults(); config.put("refreshSeconds", 9);
        assertThrows(BusinessException.class, () -> ComputeRuntimeProjection.validate(new LinkedHashMap<>(), config));
    }

    @Test void invalidRangesCannotBeSavedAndManualCapacityIsAlsoChecked() {
        for (var entry : Map.of("exaflopsMin", "4.51", "exaflopsMax", "5.01", "totalExaflops", "4.49",
                "onlineGpusMin", "20001", "onlineGpusMax", "10899", "exaflops", "invalid").entrySet()) {
            var config = ComputeRuntimeProjection.defaults();
            if (entry.getKey().equals("exaflops")) config.put("mode", "MANUAL");
            config.put(entry.getKey(), entry.getValue());
            assertThrows(BusinessException.class, () -> ComputeRuntimeProjection.validate(new LinkedHashMap<>(), config));
        }
        for (var entry : Map.of("exaflopsMin", "4.151", "onlineGpusMin", "10900.1", "exaflopsMax", "NaN").entrySet()) {
            var config = ComputeRuntimeProjection.defaults(); config.put(entry.getKey(), entry.getValue());
            assertThrows(BusinessException.class, () -> ComputeRuntimeProjection.validate(new LinkedHashMap<>(), config));
        }
        var config = ComputeRuntimeProjection.defaults(); config.put("mode", "MANUAL"); config.put("totalExaflops", "1");
        assertThrows(BusinessException.class, () -> ComputeRuntimeProjection.validate(new LinkedHashMap<>(), config));
        config.put("exaflops", "0.5");
        assertDoesNotThrow(() -> ComputeRuntimeProjection.validate(new LinkedHashMap<>(), config));
    }

    @Test void legacyUnitsAreNotReinterpretedAndProjectionNeverMutatesConfiguration() {
        var legacy = fields("enabled", true, "mode", "MANUAL", "refreshSeconds", 30, "capacity", "9999999", "activeNodes", 100,
                "consumed", "5000000", "totalNodes", 150, "runningPools", 8);
        var completed = ComputeRuntimeProjection.complete(legacy);
        assertEquals("5.0", completed.get("totalExaflops"));
        assertEquals(15000L, completed.get("onlineGpus"));
        assertEquals("MANUAL", completed.get("mode"));
        assertEquals(30, completed.get("refreshSeconds"));
        for (String key : List.of("capacity", "consumed", "activeNodes", "totalNodes", "runningPools")) assertFalse(completed.containsKey(key));
        var before = Map.copyOf(completed);
        ComputeRuntimeProjection.project(completed, Instant.parse("2026-10-17T12:00:00Z"));
        assertEquals(before, completed);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> samples(Map<String,Object> projected) { return (List<Map<String,Object>>) projected.get("samples"); }
}
