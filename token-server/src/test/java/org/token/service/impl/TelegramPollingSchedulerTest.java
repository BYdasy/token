package org.token.service.impl;

import org.token.repository.dao.NotificationChannelConfigMapper;
import org.token.model.entity.NotificationChannelConfig;
import org.token.service.NotificationConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

import org.springframework.core.task.SyncTaskExecutor;

class TelegramPollingSchedulerTest {
    private final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TelegramPollingScheduler.class);
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
    private boolean previousAdditive;

    @org.junit.jupiter.api.BeforeEach
    void captureExpectedFailures() {
        previousAdditive = logger.isAdditive(); logger.setAdditive(false); logs.start(); logger.addAppender(logs);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreLogger() {
        logger.detachAppender(logs); logger.setAdditive(previousAdditive); logs.stop();
    }

    @Test
    void invalidCredentialsAndDatabaseFailuresAreBackedOff() {
        NotificationChannelConfigMapper mapper = mock(NotificationChannelConfigMapper.class);
        NotificationConfigService service = mock(NotificationConfigService.class);
        when(mapper.telegram(true)).thenReturn(List.of(channel(1L)));
        doThrow(new TelegramApiException("INVALID_TOKEN", 1800)).when(service).pollTelegramChannel(1L);
        TelegramPollingScheduler scheduler = new TelegramPollingScheduler(new org.token.repository.system.NotificationChannelConfigRepository(mapper), service, new SyncTaskExecutor(), java.time.Clock.fixed(java.time.Instant.parse("2026-09-17T00:00:00Z"), java.time.ZoneOffset.UTC));
        scheduler.pollEnabledChannels(); scheduler.pollEnabledChannels();
        verify(service, times(1)).pollTelegramChannel(1L);

        NotificationChannelConfigMapper unavailable = mock(NotificationChannelConfigMapper.class);
        when(unavailable.telegram(true)).thenThrow(new IllegalStateException("数据库不可用"));
        TelegramPollingScheduler noDatabase = new TelegramPollingScheduler(new org.token.repository.system.NotificationChannelConfigRepository(unavailable), service, new SyncTaskExecutor());
        noDatabase.pollEnabledChannels(); noDatabase.pollEnabledChannels();
        verify(unavailable, times(1)).telegram(true);
    }

    @Test
    void rejectedTaskCanBeScheduledAgainAndShutdownStopsSubmitting() {
        NotificationChannelConfigMapper mapper = mock(NotificationChannelConfigMapper.class);
        NotificationConfigService service = mock(NotificationConfigService.class);
        NotificationChannelConfig record = channel(1L);
        when(mapper.telegram(true)).thenReturn(List.of(record));
        java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.core.task.TaskExecutor executor = task -> {
            if (submissions.incrementAndGet() == 1) throw new java.util.concurrent.RejectedExecutionException();
            task.run();
        };
        TelegramPollingScheduler scheduler = new TelegramPollingScheduler(new org.token.repository.system.NotificationChannelConfigRepository(mapper), service, executor);
        scheduler.pollEnabledChannels();
        record.setUpdatedAt(java.time.LocalDateTime.of(2026, 9, 17, 0, 0));
        scheduler.pollEnabledChannels();
        verify(service).pollTelegramChannel(1L);
        scheduler.stop(); scheduler.pollEnabledChannels();
        org.junit.jupiter.api.Assertions.assertEquals(2, submissions.get());
    }

    @Test
    void failureOfOneChannelDoesNotStopRemainingChannels() {
        NotificationChannelConfigMapper mapper = mock(NotificationChannelConfigMapper.class);
        NotificationConfigService service = mock(NotificationConfigService.class);
        when(mapper.telegram(true)).thenReturn(List.of(channel(1L), channel(2L)));
        doThrow(new IllegalStateException("bad token")).when(service).pollTelegramChannel(1L);

        assertDoesNotThrow(() -> new TelegramPollingScheduler(new org.token.repository.system.NotificationChannelConfigRepository(mapper), service, new SyncTaskExecutor()).pollEnabledChannels());

        verify(service).pollTelegramChannel(1L);
        verify(service).pollTelegramChannel(2L);
        org.junit.jupiter.api.Assertions.assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR));
    }

    @Test
    void databaseFailureDoesNotEscapeScheduledMethod() {
        NotificationChannelConfigMapper mapper = mock(NotificationChannelConfigMapper.class);
        when(mapper.telegram(true)).thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> new TelegramPollingScheduler(new org.token.repository.system.NotificationChannelConfigRepository(mapper), mock(NotificationConfigService.class), new SyncTaskExecutor()).pollEnabledChannels());
    }

    private NotificationChannelConfig channel(long id) {
        NotificationChannelConfig channel = new NotificationChannelConfig();
        channel.setId(id);
        channel.setChannelType("TELEGRAM");
        channel.setEnabled(1);
        return channel;
    }
}
