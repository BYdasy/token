package org.token.service;

import org.token.model.response.TimeZoneOptionView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeZoneCatalogServiceTest {

    private final TimeZoneCatalogService service = new TimeZoneCatalogService();

    @Test
    void emptySearchWithoutSelectedTimezoneReturnsNoOptions() {
        List<TimeZoneOptionView> result = service.search(null, 999, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void emptySearchOnlyReturnsSelectedTimezone() {
        List<TimeZoneOptionView> result = service.search(null, 999, "Asia/Shanghai");

        assertEquals(1, result.size());
        assertEquals("Asia/Shanghai", result.get(0).id());
    }

    @Test
    void chineseCountrySearchFindsMexicoAndKeepsChineseGuidanceInLabel() {
        List<TimeZoneOptionView> result = service.search("墨西哥", 999, null);

        assertFalse(result.isEmpty());
        assertTrue(result.size() <= 24);
        TimeZoneOptionView mexicoCity = result.stream()
                .filter(item -> "America/Mexico_City".equals(item.id()))
                .findFirst()
                .orElseThrow();
        assertTrue(mexicoCity.label().contains("墨西哥"));
        assertTrue(mexicoCity.label().contains("墨西哥城"));
    }

    @Test
    void chineseRegionSearchAcceptsNaturalTimezoneSuffix() {
        List<TimeZoneOptionView> result = service.search("香港时区", 30, null);

        assertEquals(1, result.stream()
                .filter(item -> "Asia/Hong_Kong".equals(item.id()))
                .count());
        TimeZoneOptionView hongKong = result.stream()
                .filter(item -> "Asia/Hong_Kong".equals(item.id()))
                .findFirst()
                .orElseThrow();
        assertTrue(hongKong.label().contains("中国香港"));
        assertTrue(hongKong.label().contains("UTC+08:00"));
    }

    @Test
    void multipleSearchTermsMustAllMatchTheSameTimezone() {
        List<TimeZoneOptionView> result = service.search("墨西哥 城", 30, null);

        assertTrue(result.stream().anyMatch(item -> "America/Mexico_City".equals(item.id())));
        assertFalse(result.stream().anyMatch(item -> "Asia/Shanghai".equals(item.id())));
    }
}
