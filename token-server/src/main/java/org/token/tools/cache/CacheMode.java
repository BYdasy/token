package org.token.tools.cache;

import java.util.Locale;

/** 后台可选的缓存运行模式。 */
public enum CacheMode {
    MEMORY,
    REDIS,
    AUTO;

    public static CacheMode parse(String value, CacheMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return CacheMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
