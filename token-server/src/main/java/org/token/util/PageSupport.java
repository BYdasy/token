package org.token.util;

import java.util.Collections;
import java.util.Map;

public final class PageSupport {
    private PageSupport() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> filters(Map<String, Object> body) {
        Object value = body == null ? null : body.get("filterParams");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static long current(Map<String, Object> body) {
        Object page = body == null ? null : body.get("page");
        Map<String, Object> pageMap = page instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Collections.emptyMap();
        return positive(pageMap.get("current"), positive(body == null ? null : body.get("current"), 1));
    }

    @SuppressWarnings("unchecked")
    public static long size(Map<String, Object> body) {
        Object page = body == null ? null : body.get("page");
        Map<String, Object> pageMap = page instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Collections.emptyMap();
        return Math.min(200, positive(pageMap.get("size"), positive(body == null ? null : body.get("size"), 20)));
    }

    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "undefined".equalsIgnoreCase(text)
                || "null".equalsIgnoreCase(text) || "nan".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    private static long positive(Object value, long fallback) {
        try {
            long number = value == null ? fallback : Long.parseLong(String.valueOf(value));
            return number > 0 ? number : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
