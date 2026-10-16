package org.token.tools.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.token.model.response.CacheStatusView;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可选 Redis 与进程内存组合缓存。
 *
 * Redis 默认关闭；即使开启后 Redis 暂时不可用，所有调用也会自动使用内存缓存，
 * 不会影响应用启动和正常业务请求。
 */
@Slf4j
@Service
public class HybridCacheService {

    private static final long REDIS_RETRY_DELAY_MILLIS = 30_000L;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final CacheRuntimePolicyService policyService;
    private final String keyPrefix;
    private final Map<String, LocalEntry> localCache = new ConcurrentHashMap<>();

    private volatile long redisUnavailableUntil;
    private volatile CachePolicySnapshot lastPolicy;

    public HybridCacheService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            CacheRuntimePolicyService policyService,
            @Value("${exp.cache.key-prefix:exp}") String keyPrefix
    ) {
        this.redisProvider = redisProvider;
        this.policyService = policyService;
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : "exp";
    }

    public String get(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        CachePolicySnapshot policy = currentPolicy();
        if (!policy.enabled(CacheFeature.fromCacheKey(key))) {
            return null;
        }
        long now = System.currentTimeMillis();
        LocalEntry local = localCache.get(key);
        if (local != null && local.expiresAt() > now) {
            return local.value();
        }
        if (local != null) {
            localCache.remove(key, local);
        }

        String remote = readRedis(key, policy);
        if (remote != null) {
            localCache.put(key, new LocalEntry(remote, now + 30_000L));
        }
        return remote;
    }

    public void put(String key, String value, Duration ttl) {
        if (!StringUtils.hasText(key) || value == null) {
            return;
        }
        CachePolicySnapshot policy = currentPolicy();
        if (!policy.enabled(CacheFeature.fromCacheKey(key))) {
            return;
        }
        Duration effectiveTtl = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Duration.ofMinutes(5)
                : ttl;
        // Redis 开启时，本地层只保留短副本，降低多实例写后其他节点继续读取旧值的时间。
        boolean wantsRedis = wantsRedis(policy);
        long localTtlMillis = wantsRedis
                ? Math.min(effectiveTtl.toMillis(), Duration.ofSeconds(30).toMillis())
                : effectiveTtl.toMillis();
        localCache.put(key, new LocalEntry(value, System.currentTimeMillis() + localTtlMillis));
        writeRedis(key, value, effectiveTtl, policy);
    }

    public void evict(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        localCache.remove(key);
        // 即使某项缓存刚被关闭，也要清除 Redis 中的旧值，避免重新开启后读到陈旧数据。
        CachePolicySnapshot policy = currentPolicy();
        if (wantsRedis(policy)) {
            executeRedis(template -> template.delete(redisKey(key)), true);
        }
    }

    public boolean isRedisEnabled() {
        CachePolicySnapshot policy = currentPolicy();
        return policy.enabled() && wantsRedis(policy);
    }

    /** 仅供已接入的公开配置/词条缓存判断开关，不缓存任何认证或资金结果。 */
    public boolean isFeatureEnabled(CacheFeature feature) {
        return currentPolicy().enabled(feature);
    }

    /** 数据库缓存策略更新后立即清空进程内副本。 */
    public void refreshPolicy() {
        policyService.invalidate();
        lastPolicy = null;
        localCache.clear();
    }

    public CacheStatusView status() {
        CachePolicySnapshot policy = currentPolicy();
        boolean redisAvailable = wantsRedis(policy) && checkRedisAvailable();
        Map<String, Boolean> features = new java.util.LinkedHashMap<>();
        for (CacheFeature feature : CacheFeature.values()) {
            if (feature != CacheFeature.OTHER) {
                features.put(feature.name(), policy.features().getOrDefault(feature, true));
            }
        }
        String effectiveMode = !policy.enabled()
                ? "DISABLED"
                : redisAvailable && wantsRedis(policy) ? "REDIS" : "MEMORY";
        return new CacheStatusView(
                policy.enabled(),
                policy.mode().name(),
                effectiveMode,
                redisAvailable,
                localCache.size(),
                Map.copyOf(features)
        );
    }

    private String readRedis(String key, CachePolicySnapshot policy) {
        if (!canUseRedis(policy)) {
            return null;
        }
        try {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            return template == null ? null : template.opsForValue().get(redisKey(key));
        } catch (Exception exception) {
            markRedisUnavailable(exception);
            return null;
        }
    }

    private void writeRedis(String key, String value, Duration ttl, CachePolicySnapshot policy) {
        if (!canUseRedis(policy)) {
            return;
        }
        executeRedis(template -> template.opsForValue().set(redisKey(key), value, ttl), false);
    }

    private void executeRedis(RedisOperation operation, boolean ignoreConfiguredMode) {
        if ((!ignoreConfiguredMode && !canUseRedis(currentPolicy()))
                || (ignoreConfiguredMode && System.currentTimeMillis() < redisUnavailableUntil)) {
            return;
        }
        try {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template != null) {
                operation.execute(template);
            }
        } catch (Exception exception) {
            markRedisUnavailable(exception);
        }
    }

    private boolean canUseRedis(CachePolicySnapshot policy) {
        return policy.enabled() && wantsRedis(policy)
                && System.currentTimeMillis() >= redisUnavailableUntil;
    }

    private boolean wantsRedis(CachePolicySnapshot policy) {
        return policy.mode() == CacheMode.REDIS || policy.mode() == CacheMode.AUTO;
    }

    private CachePolicySnapshot currentPolicy() {
        CachePolicySnapshot policy = policyService.current();
        CachePolicySnapshot previous = lastPolicy;
        if (previous == null || !previous.equals(policy)) {
            synchronized (this) {
                previous = lastPolicy;
                if (previous == null || !previous.equals(policy)) {
                    localCache.clear();
                    lastPolicy = policy;
                }
            }
        }
        return policy;
    }

    private boolean checkRedisAvailable() {
        if (System.currentTimeMillis() < redisUnavailableUntil) {
            return false;
        }
        try {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template == null || template.getConnectionFactory() == null) {
                return false;
            }
            try (RedisConnection connection = template.getConnectionFactory().getConnection()) {
                connection.ping();
            }
            return true;
        } catch (Exception exception) {
            markRedisUnavailable(exception);
            return false;
        }
    }

    private void markRedisUnavailable(Exception exception) {
        redisUnavailableUntil = System.currentTimeMillis() + REDIS_RETRY_DELAY_MILLIS;
        log.warn("Redis 暂时不可用，30 秒内使用进程内存缓存：{}", exception.getMessage());
    }

    private String redisKey(String key) {
        return keyPrefix + ":" + key;
    }

    private record LocalEntry(String value, long expiresAt) {
    }

    @FunctionalInterface
    private interface RedisOperation {
        void execute(StringRedisTemplate template);
    }
}
