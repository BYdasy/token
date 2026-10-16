package org.token.service.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramChannelLockRegistryTest {

    @Test
    void automaticPollingSkipsChannelWhileManualPollingOwnsItsLock() throws Exception {
        TelegramChannelLockRegistry registry = new TelegramChannelLockRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread manual = new Thread(() -> registry.execute(7L, () -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        manual.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        AtomicBoolean executed = new AtomicBoolean();
        assertFalse(registry.tryExecute(7L, () -> executed.set(true)));
        assertFalse(executed.get());

        release.countDown();
        manual.join(2_000);
        assertTrue(registry.tryExecute(7L, () -> executed.set(true)));
        assertTrue(executed.get());
    }
}
