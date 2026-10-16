package org.token.tools.cache;

/** 当前已经接入 HybridCacheService 的缓存功能。 */
public enum CacheFeature {
    SETTINGS("cache.settings.enabled"),
    LANGUAGES("cache.languages.enabled"),
    MENUS("cache.menus.enabled"),
    ROLES("cache.roles.enabled"),
    IP_WHITELIST("cache.ip-whitelist.enabled"),
    OTHER("");

    private final String settingKey;

    CacheFeature(String settingKey) {
        this.settingKey = settingKey;
    }

    public String settingKey() {
        return settingKey;
    }

    public static CacheFeature fromCacheKey(String key) {
        if (key == null) {
            return OTHER;
        }
        if (key.startsWith("settings:")) {
            return SETTINGS;
        }
        if (key.startsWith("i18n:")) {
            return LANGUAGES;
        }
        if (key.startsWith("system:enabled-menus")) {
            return MENUS;
        }
        if (key.startsWith("system:role:" ) || key.startsWith("system:roles:")) {
            return ROLES;
        }
        if (key.startsWith("security:ip-whitelist")) {
            return IP_WHITELIST;
        }
        return OTHER;
    }
}
