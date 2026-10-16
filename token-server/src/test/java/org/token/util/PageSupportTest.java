package org.token.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PageSupportTest {
    @Test
    void ignoresFrontendEmptyTokens() {
        assertNull(PageSupport.text(null));
        assertNull(PageSupport.text(""));
        assertNull(PageSupport.text("undefined"));
        assertNull(PageSupport.text("NULL"));
        assertNull(PageSupport.text("NaN"));
        assertEquals("1", PageSupport.text(" 1 "));
    }

    @Test
    void invalidPaginationFallsBackSafely() {
        Map<String, Object> body = Map.of("page", Map.of("current", "undefined", "size", "NaN"));
        assertEquals(1, PageSupport.current(body));
        assertEquals(20, PageSupport.size(body));
    }
}
