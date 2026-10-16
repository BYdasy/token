package org.token.tools.cache;

import java.util.EnumMap;
import java.util.Map;

/** 从 system_setting 读取的缓存策略快照。 */
public record CachePolicySnapshot(
        boolean enabled,
        CacheMode mode,
        Map<CacheFeature, Boolean> features
) {
    public CachePolicySnapshot {
        EnumMap<CacheFeature, Boolean> copy = new EnumMap<>(CacheFeature.class);
        if (features != null) {
            copy.putAll(features);
        }
        for (CacheFeature feature : CacheFeature.values()) {
            copy.putIfAbsent(feature, true);
        }
        features = Map.copyOf(copy);
    }

    public boolean enabled(CacheFeature feature) {
        return enabled && features.getOrDefault(feature == null ? CacheFeature.OTHER : feature, true);
    }
}
