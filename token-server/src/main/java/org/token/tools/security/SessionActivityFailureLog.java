package org.token.tools.security;

/** 续期存储故障保留真实堆栈，持续故障每分钟汇总一次，避免污染已成功提交的响应。 */
public final class SessionActivityFailureLog {
    private final java.util.concurrent.atomic.AtomicLong nextLogAt = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();

    public void record(org.slf4j.Logger logger, String message, RuntimeException exception) {
        failures.incrementAndGet();
        long now = System.currentTimeMillis(), next = nextLogAt.get();
        if (now >= next && nextLogAt.compareAndSet(next, now + 60_000)) {
            logger.error("{}；本轮失败 {} 次，后续持续故障按分钟汇总", message, failures.getAndSet(0), exception);
        }
    }
}
