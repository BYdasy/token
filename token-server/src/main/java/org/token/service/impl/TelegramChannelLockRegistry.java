package org.token.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Telegram 渠道的实例内互斥锁。
 *
 * 手动获取更新会等待当前轮询结束；自动轮询遇到忙碌渠道则直接跳过，
 * 从而保证同一实例不会使用同一个 offset 重复消费同一批更新。
 */
@Component
public class TelegramChannelLockRegistry {

    private final ConcurrentMap<Long, ReentrantLock> channelLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> botLocks = new ConcurrentHashMap<>();
    private final org.token.repository.system.TelegramAdvisoryLockRepository repository;

    @Autowired
    public TelegramChannelLockRegistry(org.token.repository.system.TelegramAdvisoryLockRepository repository) { this.repository = repository; }

    /** 隔离单元测试不建立数据库连接。生产构造器必须提供 DataSource。 */
    TelegramChannelLockRegistry() { this.repository = null; }

    /** 同一机器人跨渠道、跨进程只能有一个消费者；MySQL 命名锁随专用连接关闭自动释放。 */
    public <T> T executeBot(String token, Supplier<T> action) {
        String key = TelegramBotClient.fingerprint(token);
        ReentrantLock local = botLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        if (!local.tryLock()) throw new TelegramApiException("BUSY", 2);
        try {
            if (repository == null) return action.get();
            try (var lease = repository.tryAcquire(key)) {
                if (lease == null) throw new TelegramApiException("BUSY",2);
                return action.get();
            }
        } finally {
            local.unlock();
        }
    }

    public <T> T execute(Long channelId, Supplier<T> action) {
        ReentrantLock lock = lockFor(channelId);
        try {
            if (!lock.tryLock(2, java.util.concurrent.TimeUnit.SECONDS)) throw new TelegramApiException("BUSY", 2);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TelegramApiException("BUSY", 2);
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public boolean tryExecute(Long channelId, Runnable action) {
        ReentrantLock lock = lockFor(channelId);
        if (!lock.tryLock()) {
            return false;
        }
        try {
            action.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(Long channelId) {
        if (channelId == null) {
            throw new IllegalArgumentException("Telegram 渠道 ID 不能为空");
        }
        return channelLocks.computeIfAbsent(channelId, ignored -> new ReentrantLock());
    }
}
