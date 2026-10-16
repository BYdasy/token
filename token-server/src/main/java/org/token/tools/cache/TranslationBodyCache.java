package org.token.tools.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** 仅缓存公开翻译正文；版本与译文同事务提交，其他实例按两秒间隔复核版本。 */
@Service
public class TranslationBodyCache {
    public static final String REVISION_KEY = "i18n.translations.revision";
    private static final Set<Integer> TYPES = Set.of(1, 5, 7);
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final long REVISION_NANOS = Duration.ofSeconds(2).toNanos();
    private final HybridCacheService cache;
    private final org.token.repository.system.SystemSettingRepository repository;
    private final ObjectMapper json;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    private final LongSupplier nanoTime;
    private volatile Revision revision = new Revision("", 0);
    private long localGeneration;
    private final Object revisionMonitor = new Object();

    @org.springframework.beans.factory.annotation.Autowired
    public TranslationBodyCache(HybridCacheService cache, org.token.repository.system.SystemSettingRepository repository, ObjectMapper json,
                                PlatformTransactionManager transactions) {
        this(cache, repository, json, transactions, System::nanoTime);
    }

    public TranslationBodyCache(HybridCacheService cache, JdbcTemplate jdbc, ObjectMapper json,
                                PlatformTransactionManager transactions) {
        this(cache, new org.token.repository.system.SystemSettingRepository(jdbc), json, transactions, System::nanoTime);
    }

    TranslationBodyCache(HybridCacheService cache, JdbcTemplate jdbc, ObjectMapper json,
                         PlatformTransactionManager transactions, LongSupplier nanoTime) {
        this(cache,new org.token.repository.system.SystemSettingRepository(jdbc),json,transactions,nanoTime);
    }

    private TranslationBodyCache(HybridCacheService cache, org.token.repository.system.SystemSettingRepository repository, ObjectMapper json,
                         PlatformTransactionManager transactions, LongSupplier nanoTime) {
        this.cache = cache;
        this.repository = repository;
        this.json = json;
        this.nanoTime = nanoTime;
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        write = new TransactionTemplate(transactions);
    }

    public String message(String identity, Supplier<String> loader) {
        return messages(1, identity, () -> Map.of("text", loader.get())).get("text");
    }

    public Map<String, String> messages(int type, String identity, Supplier<Map<String, String>> loader) {
        // 已有事务可能持有旧快照或未提交修改：不读/写共享缓存，也不另占第二个连接。
        if (!TYPES.contains(type) || TransactionSynchronizationManager.isActualTransactionActive()
                || !cache.isFeatureEnabled(CacheFeature.LANGUAGES)) return copy(loader.get());
        String current = currentRevision();
        if (current.isEmpty()) return copy(loader.get()); // SQL尚未执行或版本读取失败时保持直接查询。
        String fingerprint = fingerprint(type + ":" + identity);
        // 固定槽位限制本地及Redis键空间：单词条256槽，两类整包各64槽；碰撞只会触发重新加载。
        String key = "i18n:body:v1:" + type + ":" + Math.floorMod(fingerprint.hashCode(), type == 1 ? 256 : 64);
        String cached = cache.get(key);
        if (cached != null) {
            try {
                Entry entry = json.readValue(cached, Entry.class);
                if (current.equals(entry.revision()) && fingerprint.equals(entry.fingerprint()) && entry.messages() != null
                        && entry.expiresAtMillis() > System.currentTimeMillis())
                    return copy(entry.messages());
            } catch (Exception ignored) { /* 损坏或旧格式只触发重新加载，不伪装成缺译。 */ }
        }
        long generation;
        synchronized (revisionMonitor) { generation = localGeneration; }
        Loaded loaded = read.execute(status -> {
            String before = readRevision();
            Map<String, String> values = copy(loader.get());
            String after = readRevision();
            return new Loaded(before.equals(after) ? before : "", values);
        });
        if (loaded == null) throw new IllegalStateException("翻译缓存加载未返回结果");
        // 旧加载即使迟到，也不能成为新版本缓存。跨实例旧版本包同样会被版本校验拒绝。
        String publicationRevision = currentRevision();
        boolean cachingEnabled = cache.isFeatureEnabled(CacheFeature.LANGUAGES);
        boolean publish;
        synchronized (revisionMonitor) {
            publish = generation == localGeneration && !loaded.revision().isEmpty()
                    && loaded.revision().equals(publicationRevision) && cachingEnabled;
        }
        // Redis或策略查询绝不能持有版本锁；提交后回调仍持有待归还的事务连接。
        // 若检查之后发生提交，迟到写入仍携带旧版本，新版本读取会拒绝该包。
        if (publish) {
            try {
                String value = json.writeValueAsString(new Entry(loaded.revision(), fingerprint, loaded.messages(),
                        System.currentTimeMillis() + TTL.toMillis()));
                int maxBytes = type == 1 ? 16_384 : 1_048_576;
                if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) cache.put(key, value, TTL);
            } catch (Exception ignored) { /* 缓存序列化失败不影响已成功的真实读取。 */ }
        }
        return copy(loaded.messages());
    }

    /** 由已审计的1/5/7写入口调用；一笔事务只换一次版本，回滚不发布。 */
    public void translationsChanged(Integer type) {
        if (type == null || !TYPES.contains(type)) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("翻译修改必须与缓存版本处于同一事务");
        // 同步对象随事务挂起/恢复，不能把自定义线程资源误当成REQUIRES_NEW内层事务的标记。
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations())
            if (synchronization instanceof RevisionChange change && change.owner == this) return;
        TransactionSynchronizationManager.registerSynchronization(new RevisionChange());
    }

    private final class RevisionChange implements TransactionSynchronization {
            private final TranslationBodyCache owner = TranslationBodyCache.this;
            private final String next = UUID.randomUUID().toString();
            @Override public void beforeCommit(boolean readOnly) {
                if (readOnly) throw new IllegalStateException("只读事务不能修改翻译缓存版本");
                repository.updateTranslationRevision(next,java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            }
            @Override public void afterCommit() {
                synchronized (revisionMonitor) {
                    localGeneration++;
                    // 缺少版本行时不能凭空开启缓存；下一次读取重新复核持久版本。
                    revision = new Revision("", 0);
                }
            }
    }

    /** 自动补译逐条短事务；某条失败不会撤销先前已成功的补译或漏掉其版本更新。 */
    public int writeTranslation(Integer type, IntSupplier insert) {
        if (type == null || !TYPES.contains(type)) return insert.getAsInt();
        Integer changed = write.execute(status -> {
            int count = insert.getAsInt();
            if (count > 0) translationsChanged(type);
            return count;
        });
        return changed == null ? 0 : changed;
    }

    private String currentRevision() {
        Revision snapshot = revision;
        long now = nanoTime.getAsLong();
        if (now < snapshot.expiresAt()) return snapshot.value();
        long generation;
        synchronized (revisionMonitor) {
            snapshot = revision;
            if (nanoTime.getAsLong() < snapshot.expiresAt()) return snapshot.value();
            generation = localGeneration;
        }
        String value;
        try { value = read.execute(status -> readRevision()); }
        catch (RuntimeException ignored) { value = ""; }
        synchronized (revisionMonitor) {
            // 只发布到本次读取开始时的快照；不能覆盖期间的提交失效或另一读取已发布的新版本。
            if (generation == localGeneration && revision == snapshot)
                revision = new Revision(value == null ? "" : value, nanoTime.getAsLong() + REVISION_NANOS);
            return revision.value();
        }
    }

    private String readRevision() {
        var values = repository.values(REVISION_KEY);
        if (values.size() != 1 || values.get(0) == null) return "";
        String value = values.get(0);
        return value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ? value : "";
    }

    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Map<String, String> copy(Map<String, String> value) { return new LinkedHashMap<>(value); }
    private record Revision(String value, long expiresAt) { }
    private record Loaded(String revision, Map<String, String> messages) { }
    public record Entry(String revision, String fingerprint, Map<String, String> messages, long expiresAtMillis) { }
}
