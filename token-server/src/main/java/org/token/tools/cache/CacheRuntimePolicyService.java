package org.token.tools.cache;

import lombok.extern.slf4j.Slf4j;
import org.token.repository.system.SystemSettingRepository;
import org.token.model.entity.SystemSetting;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存策略直接读取 system_setting，不再经过 HybridCacheService，
 * 从而避免“读取缓存设置本身还依赖缓存设置”的循环。
 */
@Slf4j
@Service
public class CacheRuntimePolicyService {

    public static final String CACHE_ENABLED = "cache.enabled";
    public static final String CACHE_MODE = "cache.mode";

    private static final long REFRESH_INTERVAL_MILLIS = 2_000L;

    private final SystemSettingRepository settingRepository;
    private final CacheMode defaultMode;

    private volatile CachePolicySnapshot snapshot;
    private volatile long refreshAfter;

    public CacheRuntimePolicyService(
            SystemSettingRepository settingRepository,
            @Value("${exp.cache.redis-enabled:false}") boolean redisEnabledByYaml
    ) {
        this.settingRepository = settingRepository;
        this.defaultMode = redisEnabledByYaml ? CacheMode.AUTO : CacheMode.MEMORY;
        this.snapshot = defaults();
    }

    public CachePolicySnapshot current() {
        long now = System.currentTimeMillis();
        CachePolicySnapshot current = snapshot;
        if (now < refreshAfter) {
            return current;
        }
        synchronized (this) {
            if (now < refreshAfter) {
                return snapshot;
            }
            snapshot = load();
            refreshAfter = now + REFRESH_INTERVAL_MILLIS;
            return snapshot;
        }
    }

    public void invalidate() {
        refreshAfter = 0L;
    }

    private CachePolicySnapshot load() {
        Map<String, String> values = new java.util.HashMap<>();
        try {
            List<String> keys = new java.util.ArrayList<>();
            keys.add(CACHE_ENABLED);
            keys.add(CACHE_MODE);
            for (CacheFeature feature : CacheFeature.values()) {
                if (StringUtils.hasText(feature.settingKey())) {
                    keys.add(feature.settingKey());
                }
            }
            List<SystemSetting> settings = settingRepository.findByKeys(keys);
            for (SystemSetting setting : settings) {
                if (setting != null && StringUtils.hasText(setting.getSettingKey())) {
                    values.put(setting.getSettingKey(), setting.getSettingValue());
                }
            }
        } catch (Exception exception) {
            // 增量 SQL 尚未执行时继续使用兼容默认值，避免影响应用启动。
            log.debug("读取数据库缓存策略失败，使用默认策略：{}", exception.getMessage());
            return defaults();
        }

        EnumMap<CacheFeature, Boolean> features = new EnumMap<>(CacheFeature.class);
        for (CacheFeature feature : CacheFeature.values()) {
            if (StringUtils.hasText(feature.settingKey())) {
                features.put(feature, booleanValue(values.get(feature.settingKey()), true));
            }
        }
        features.put(CacheFeature.OTHER, true);
        return new CachePolicySnapshot(
                booleanValue(values.get(CACHE_ENABLED), true),
                CacheMode.parse(values.get(CACHE_MODE), defaultMode),
                features
        );
    }

    private CachePolicySnapshot defaults() {
        EnumMap<CacheFeature, Boolean> features = new EnumMap<>(CacheFeature.class);
        for (CacheFeature feature : CacheFeature.values()) {
            features.put(feature, true);
        }
        return new CachePolicySnapshot(true, defaultMode, features);
    }

    private boolean booleanValue(String value, boolean fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> fallback;
        };
    }
}
