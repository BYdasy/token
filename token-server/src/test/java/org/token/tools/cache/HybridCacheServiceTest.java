package org.token.tools.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridCacheServiceTest {

    @Test
    void memoryModeWorksAndFeatureSwitchTakesEffectImmediately() {
        CacheRuntimePolicyService policyService = mock(CacheRuntimePolicyService.class);
        EnumMap<CacheFeature, Boolean> enabledFeatures = allFeatures(true);
        when(policyService.current()).thenReturn(new CachePolicySnapshot(true, CacheMode.MEMORY, enabledFeatures));
        HybridCacheService cache = new HybridCacheService(redisProvider(), policyService, "test");

        cache.put("system:role:1", "cached", Duration.ofMinutes(1));
        assertEquals("cached", cache.get("system:role:1"));

        EnumMap<CacheFeature, Boolean> disabledRoles = allFeatures(true);
        disabledRoles.put(CacheFeature.ROLES, false);
        when(policyService.current()).thenReturn(new CachePolicySnapshot(true, CacheMode.MEMORY, disabledRoles));
        assertNull(cache.get("system:role:1"));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<StringRedisTemplate> redisProvider() {
        return mock(ObjectProvider.class);
    }

    private EnumMap<CacheFeature, Boolean> allFeatures(boolean value) {
        EnumMap<CacheFeature, Boolean> features = new EnumMap<>(CacheFeature.class);
        for (CacheFeature feature : CacheFeature.values()) {
            features.put(feature, value);
        }
        return features;
    }
}
