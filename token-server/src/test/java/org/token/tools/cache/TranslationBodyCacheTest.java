package org.token.tools.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class TranslationBodyCacheTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    HybridCacheService hybrid;
    TranslationBodyCache body;
    AtomicLong time;
    AtomicReference<CachePolicySnapshot> policy;
    ObjectProvider<StringRedisTemplate> redis;

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:translation-cache-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE system_setting(setting_key VARCHAR(100) PRIMARY KEY,setting_value VARCHAR(200),updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES(?,?)", TranslationBodyCache.REVISION_KEY, UUID.randomUUID().toString());
        jdbc.execute("CREATE TABLE word(id INT PRIMARY KEY,body VARCHAR(200))");
        jdbc.update("INSERT INTO word VALUES(1,'旧译文')");
        transactions = new DataSourceTransactionManager(source);
        time = new AtomicLong(1);
        policy = new AtomicReference<>(new CachePolicySnapshot(true, CacheMode.MEMORY, Map.of()));
        CacheRuntimePolicyService settings = mock(CacheRuntimePolicyService.class);
        when(settings.current()).thenAnswer(call -> policy.get());
        redis = mock(ObjectProvider.class);
        hybrid = new HybridCacheService(redis, settings, "test");
        body = new TranslationBodyCache(hybrid, jdbc, new ObjectMapper(), transactions, time::get);
    }

    String word() { return jdbc.queryForObject("SELECT body FROM word WHERE id=1", String.class); }
    String revision() { return jdbc.queryForObject("SELECT setting_value FROM system_setting WHERE setting_key=?", String.class, TranslationBodyCache.REVISION_KEY); }
    void update(String value) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE word SET body=? WHERE id=1", value);
            body.translationsChanged(5);
        });
    }
    Map<String,String> read(TranslationBodyCache cache) { return cache.messages(5,"en",()->Map.of("admin.word",word())); }

    @Test void allTypesHitReturnCopiesAndSwitchesBypassImmediately() {
        AtomicInteger calls = new AtomicInteger();
        for (int type : new int[]{1,5,7}) {
            Supplier<Map<String,String>> load = () -> { calls.incrementAndGet();return Map.of("word", word()); };
            var first = body.messages(type,"same",load);
            first.put("word","调用者改动");
            assertEquals("旧译文",body.messages(type,"same",load).get("word"));
        }
        assertEquals(3,calls.get());
        policy.set(new CachePolicySnapshot(true,CacheMode.MEMORY,Map.of(CacheFeature.LANGUAGES,false)));
        jdbc.update("UPDATE word SET body='开关关闭后修改'");
        assertEquals("开关关闭后修改",body.message("same",this::word));
        policy.set(new CachePolicySnapshot(false,CacheMode.MEMORY,Map.of()));
        assertEquals("开关关闭后修改",body.messages(5,"same",()->Map.of("word",word())).get("word"));
    }

    @Test void commitInvalidatesImmediatelyRollbackAndUncommittedReadsDoNotPublish() {
        assertEquals("旧译文",read(body).get("admin.word"));
        String previous = revision();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE word SET body='未提交'");
            body.translationsChanged(1);
            assertEquals("未提交",read(body).get("admin.word"));
            assertEquals(previous,revision());
            status.setRollbackOnly();
        });
        assertEquals(previous,revision());
        assertEquals("旧译文",read(body).get("admin.word"));
        update("新译文");
        assertNotEquals(previous,revision());
        assertEquals("新译文",read(body).get("admin.word"));
    }

    @Test void lateOldLoadCannotRefillTheNewGeneration() throws Exception {
        CountDownLatch readOld = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var old = executor.submit(() -> body.messages(5,"en",()->{
                String value=word();readOld.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return Map.of("admin.word",value);
            }));
            assertTrue(readOld.await(5,TimeUnit.SECONDS));
            update("提交后的译文");
            release.countDown();
            assertEquals("旧译文",old.get(5,TimeUnit.SECONDS).get("admin.word"));
            assertEquals("提交后的译文",read(body).get("admin.word"));
            assertEquals("提交后的译文",read(body).get("admin.word"));
        } finally { release.countDown();executor.shutdownNow(); }
    }

    @Test void oldReadOnlySnapshotIsNeverPublishedAsTheCurrentVersion() throws Exception {
        TransactionTemplate old = new TransactionTemplate(transactions);
        old.setReadOnly(true);old.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var executor = Executors.newSingleThreadExecutor();
        try {
            old.executeWithoutResult(status -> {
                assertEquals("旧译文",word());
                try { executor.submit(() -> update("已经提交的新译文")).get(5,TimeUnit.SECONDS); }
                catch(Exception e){throw new RuntimeException(e);}
                assertEquals("旧译文",read(body).get("admin.word"));
            });
            assertEquals("已经提交的新译文",read(body).get("admin.word"));
        } finally { executor.shutdownNow(); }
    }

    @Test void otherInstanceAndRedisFailureNeverResurrectAnOldRevision() {
        policy.set(new CachePolicySnapshot(true,CacheMode.AUTO,Map.of()));
        StringRedisTemplate template=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);
        Map<String,String> remote=new ConcurrentHashMap<>();
        when(redis.getIfAvailable()).thenReturn(template);when(template.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call->remote.get(call.getArgument(0)));
        doAnswer(call->{remote.put(call.getArgument(0),call.getArgument(1));return null;}).when(values).set(anyString(),anyString(),any(Duration.class));
        CacheRuntimePolicyService settings=mock(CacheRuntimePolicyService.class);
        when(settings.current()).thenAnswer(call->policy.get());
        var second=new TranslationBodyCache(new HybridCacheService(redis,settings,"test"),jdbc,new ObjectMapper(),transactions,time::get);
        assertEquals("旧译文",read(body).get("admin.word"));
        assertEquals("旧译文",read(second).get("admin.word"));
        update("跨实例新译文");
        doThrow(new IllegalStateException("Redis不可用")).when(values).set(anyString(),anyString(),any(Duration.class));
        assertEquals("跨实例新译文",read(body).get("admin.word"));
        time.addAndGet(Duration.ofSeconds(3).toNanos());
        assertEquals("跨实例新译文",read(second).get("admin.word"));
        // Redis仍保存旧正文，第三实例也必须按数据库版本拒绝它。
        var third=new TranslationBodyCache(new HybridCacheService(redis,settings,"test"),jdbc,new ObjectMapper(),transactions,time::get);
        assertEquals("跨实例新译文",read(third).get("admin.word"));
    }

    @Test void partialCompletionPersistsEachSuccessfulRevisionAndNoOpDoesNotBump() {
        String original=revision();
        body.writeTranslation(7,()->jdbc.update("UPDATE word SET body='第一条成功'"));
        String first=revision();assertNotEquals(original,first);
        assertThrows(IllegalStateException.class,()->body.writeTranslation(7,()->{jdbc.update("UPDATE word SET body='第二条失败'");throw new IllegalStateException("失败");}));
        assertEquals("第一条成功",word());assertEquals(first,revision());
        body.writeTranslation(7,()->0);assertEquals(first,revision());
    }

    @Test void absentRevisionAndErrorsAreNotNegativeCachedAndExternalRevisionIsObserved() {
        assertThrows(IllegalStateException.class,()->body.messages(5,"error",()->{throw new IllegalStateException("查询失败");}));
        assertEquals("旧译文",body.messages(5,"error",()->Map.of("value",word())).get("value"));
        jdbc.update("UPDATE word SET body='SQL更新'");
        jdbc.update("UPDATE system_setting SET setting_value=?",UUID.randomUUID().toString());
        time.addAndGet(Duration.ofSeconds(3).toNanos());
        assertEquals("SQL更新",body.messages(5,"error",()->Map.of("value",word())).get("value"));
        jdbc.update("DELETE FROM system_setting");time.addAndGet(Duration.ofSeconds(3).toNanos());
        AtomicInteger count=new AtomicInteger();
        for(int i=0;i<2;i++)body.message("missing",()->"实时"+count.incrementAndGet());
        assertEquals(2,count.get());
    }

    @Test void arbitraryKeysHaveBoundedSlotsAndCollisionsDoNotReturnOtherMessages() {
        for(int i=0;i<700;i++) {
            int value=i;assertEquals("值"+i,body.message("任意key"+i,()->"值"+value));
        }
        Map<?,?> entries=(Map<?,?>)ReflectionTestUtils.getField(hybrid,"localCache");
        assertNotNull(entries);assertTrue(entries.size()<=256);
        for(int i=0;i<700;i++) {int value=i;assertEquals("值"+i,body.message("任意key"+i,()->"值"+value));}
    }

    @Test void requiresNewHasIndependentInvalidationWhenOuterRollsBackOrCommits() {
        jdbc.update("INSERT INTO word VALUES(2,'内层旧译文')");
        TransactionTemplate outer=new TransactionTemplate(transactions),inner=new TransactionTemplate(transactions);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String initial=revision();
        AtomicReference<String> innerRevision=new AtomicReference<>();
        outer.executeWithoutResult(status->{
            jdbc.update("UPDATE word SET body='外层将回滚' WHERE id=1");body.translationsChanged(1);
            inner.executeWithoutResult(nested->{
                jdbc.update("UPDATE word SET body='内层已提交' WHERE id=2");body.translationsChanged(7);
            });
            innerRevision.set(revision());
            status.setRollbackOnly();
        });
        assertNotEquals(initial,innerRevision.get());assertEquals(innerRevision.get(),revision());
        assertEquals("旧译文",word());
        assertEquals("内层已提交",jdbc.queryForObject("SELECT body FROM word WHERE id=2",String.class));
        outer.executeWithoutResult(status->{
            jdbc.update("UPDATE word SET body='外层也提交' WHERE id=1");body.translationsChanged(1);
            inner.executeWithoutResult(nested->{
                jdbc.update("UPDATE word SET body='内层第二次提交' WHERE id=2");body.translationsChanged(5);
            });
            innerRevision.set(revision());
        });
        assertNotEquals(innerRevision.get(),revision());
        assertEquals("外层也提交",read(body).get("admin.word"));
    }

    @Test void oneCommittedEditInvalidatesAllTypesAndFallbackLanguages() {
        for(int type:new int[]{1,5,7})for(String language:new String[]{"en","es-mx"})
            assertEquals("旧译文",body.messages(type,language,()->Map.of("word",word())).get("word"));
        update("英语基础内容已修改");
        for(int type:new int[]{1,5,7})for(String language:new String[]{"en","es-mx"})
            assertEquals("英语基础内容已修改",body.messages(type,language,()->Map.of("word",word())).get("word"));
    }

    @Test void absoluteBodyExpiryIsNotExtendedByTheHybridLocalCopy() throws Exception {
        AtomicInteger loads=new AtomicInteger();
        Supplier<String> loader=()->"读取"+loads.incrementAndGet();
        assertEquals("读取1",body.message("expiry",loader));
        Map<String,?> entries=(Map<String,?>)ReflectionTestUtils.getField(hybrid,"localCache");
        assertNotNull(entries);
        String key=entries.keySet().iterator().next();
        ObjectMapper mapper=new ObjectMapper();
        var entry=mapper.readValue(hybrid.get(key),TranslationBodyCache.Entry.class);
        // 模拟Redis旧包刚进入仍有效的本地层，但正文自己的绝对期限已到。
        hybrid.put(key,mapper.writeValueAsString(new TranslationBodyCache.Entry(entry.revision(),entry.fingerprint(),entry.messages(),System.currentTimeMillis()-1)),Duration.ofMinutes(10));
        assertEquals("读取2",body.message("expiry",loader));
    }

    @Test void commitReturnsItsOnlyPoolConnectionWhileRevisionReaderWaitsForIt() throws Exception {
        var pool=new com.zaxxer.hikari.HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:mem:translation-pool-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        pool.setMaximumPoolSize(1);pool.setMinimumIdle(1);pool.setConnectionTimeout(2500);
        var db=new JdbcTemplate(pool);
        db.execute("CREATE TABLE system_setting(setting_key VARCHAR(100) PRIMARY KEY,setting_value VARCHAR(200),updated_at TIMESTAMP)");
        db.update("INSERT INTO system_setting(setting_key,setting_value) VALUES(?,?)",TranslationBodyCache.REVISION_KEY,UUID.randomUUID().toString());
        db.execute("CREATE TABLE word(id INT PRIMARY KEY,body VARCHAR(100))");db.update("INSERT INTO word VALUES(1,'旧值')");
        var manager=new DataSourceTransactionManager(pool);
        var limited=new TranslationBodyCache(hybrid,db,new ObjectMapper(),manager,time::get);
        CountDownLatch held=new CountDownLatch(1),commit=new CountDownLatch(1);
        var threads=Executors.newFixedThreadPool(2);
        try {
            var writer=threads.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                db.update("UPDATE word SET body='已提交'");limited.translationsChanged(5);held.countDown();
                try { assertTrue(commit.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            var reader=threads.submit(()->limited.message("pool",()->db.queryForObject("SELECT body FROM word",String.class)));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0&&System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(1,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
            commit.countDown();
            writer.get(1,TimeUnit.SECONDS); // 不能等读请求的借连接超时才归还原事务连接。
            assertEquals("已提交",reader.get(3,TimeUnit.SECONDS));
        } finally {commit.countDown();threads.shutdownNow();threads.awaitTermination(4,TimeUnit.SECONDS);pool.close();}
    }

    @Test void blockedRedisPutDoesNotBlockCommitAndItsLateOldEnvelopeIsRejected() throws Exception {
        policy.set(new CachePolicySnapshot(true,CacheMode.AUTO,Map.of()));
        StringRedisTemplate template=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class);
        Map<String,String> remote=new ConcurrentHashMap<>();
        when(redis.getIfAvailable()).thenReturn(template);when(template.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call->remote.get(call.getArgument(0)));
        CountDownLatch inRedis=new CountDownLatch(1),releaseRedis=new CountDownLatch(1);
        AtomicInteger writes=new AtomicInteger();
        doAnswer(call->{
            if(writes.incrementAndGet()==1){inRedis.countDown();assertTrue(releaseRedis.await(5,TimeUnit.SECONDS));}
            remote.put(call.getArgument(0),call.getArgument(1));return null;
        }).when(values).set(anyString(),anyString(),any(Duration.class));
        var threads=Executors.newFixedThreadPool(2);
        try {
            var reader=threads.submit(()->read(body));
            assertTrue(inRedis.await(5,TimeUnit.SECONDS));
            threads.submit(()->update("新提交")).get(1,TimeUnit.SECONDS);
            assertEquals("新提交",read(body).get("admin.word"));
            releaseRedis.countDown();assertEquals("旧译文",reader.get(3,TimeUnit.SECONDS).get("admin.word"));
            CacheRuntimePolicyService settings=mock(CacheRuntimePolicyService.class);when(settings.current()).thenAnswer(call->policy.get());
            var other=new TranslationBodyCache(new HybridCacheService(redis,settings,"test"),jdbc,new ObjectMapper(),transactions,time::get);
            assertEquals("新提交",read(other).get("admin.word"));
        } finally {releaseRedis.countDown();threads.shutdownNow();threads.awaitTermination(4,TimeUnit.SECONDS);}
    }
}
