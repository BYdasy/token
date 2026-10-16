package org.token.model.response;

import java.util.Map;

/** 缓存管理面板使用的当前配置与实际运行状态。 */
public record CacheStatusView(
        boolean enabled,
        String configuredMode,
        String effectiveMode,
        boolean redisAvailable,
        int localEntryCount,
        Map<String, Boolean> features
) {
}
