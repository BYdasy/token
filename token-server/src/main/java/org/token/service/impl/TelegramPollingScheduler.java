package org.token.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.token.repository.system.NotificationChannelConfigRepository;
import org.token.model.entity.NotificationChannelConfig;
import org.token.service.NotificationConfigService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import jakarta.annotation.PreDestroy;

/** 自动轮询全部已启用的 Telegram 机器人，并响应聊天 ID 查询命令。 */
@Slf4j
@Component
public class TelegramPollingScheduler {

    private final NotificationChannelConfigRepository channelRepository;
    private final NotificationConfigService notificationConfigService;
    private final TaskExecutor telegramPollingTaskExecutor;
    private final Set<Long> activeChannelIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, RetryState> retries = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Clock clock;
    private long databaseRetryAt;
    private int databaseFailures;

    private record RetryState(long retryAt, int failures, String status, LocalDateTime configVersion) { }

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramPollingScheduler(
            NotificationChannelConfigRepository channelRepository,
            NotificationConfigService notificationConfigService,
            @Qualifier("telegramPollingTaskExecutor") TaskExecutor telegramPollingTaskExecutor
    ) {
        this(channelRepository, notificationConfigService, telegramPollingTaskExecutor, Clock.systemUTC());
    }

    TelegramPollingScheduler(NotificationChannelConfigRepository channelRepository,
                             NotificationConfigService notificationConfigService,
                             TaskExecutor telegramPollingTaskExecutor, Clock clock) {
        this.channelRepository = channelRepository;
        this.notificationConfigService = notificationConfigService;
        this.telegramPollingTaskExecutor = telegramPollingTaskExecutor;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${exp.notification.telegram-poll-interval-ms:1000}",
            initialDelayString = "${exp.notification.telegram-poll-interval-ms:1000}"
    )
    public void pollEnabledChannels() {
        if (stopping.get() || clock.millis() < databaseRetryAt) return;
        List<NotificationChannelConfig> channels;
        try {
            channels = channelRepository.telegram(true);
        } catch (Exception exception) {
            // 数据库短暂不可用不能阻止应用启动，也不能终止后续调度。
            databaseFailures = Math.min(10, databaseFailures + 1);
            long delay = Math.min(300_000, 5_000L << Math.min(databaseFailures - 1, 6));
            databaseRetryAt = clock.millis() + delay;
            if (databaseFailures == 1 || databaseFailures % 5 == 0) {
                log.warn("Telegram 启用渠道读取失败，{} 秒后重试（连续 {} 次）", delay / 1000, databaseFailures, exception);
            }
            return;
        }
        if (databaseFailures > 0) log.info("Telegram 渠道数据库访问已恢复");
        databaseFailures = 0;
        databaseRetryAt = 0;

        if (channels == null || channels.isEmpty()) {
            return;
        }
        for (NotificationChannelConfig channel : channels) {
            if (channel == null || channel.getId() == null) {
                continue;
            }
            Long channelId = channel.getId();
            RetryState retry = retries.get(channelId);
            if (retry != null && clock.millis() < retry.retryAt()
                    && java.util.Objects.equals(retry.configVersion(), channel.getUpdatedAt())) continue;
            if (!activeChannelIds.add(channelId)) {
                continue;
            }
            try {
                telegramPollingTaskExecutor.execute(() -> pollChannel(channel));
            } catch (RuntimeException exception) {
                activeChannelIds.remove(channelId);
                if (!stopping.get()) rememberFailure(channel, new TelegramApiException("BUSY", 5));
            }
        }
    }

    private void pollChannel(NotificationChannelConfig channel) {
        Long channelId = channel.getId();
        try {
            if (stopping.get()) return;
            notificationConfigService.pollTelegramChannel(channelId);
            RetryState previous = retries.remove(channelId);
            if (previous != null && !"BUSY".equals(previous.status())) log.info("Telegram 渠道轮询已恢复，渠道：{}", channelId);
        } catch (Exception exception) {
            // 单个机器人异常只影响本轮该渠道，其余渠道继续处理。
            rememberFailure(channel, exception);
        } finally {
            activeChannelIds.remove(channelId);
        }
    }

    private void rememberFailure(NotificationChannelConfig channel, Exception exception) {
        RetryState previous = retries.get(channel.getId());
        int failures = previous == null ? 1 : Math.min(20, previous.failures() + 1);
        String status = exception instanceof TelegramApiException api ? api.status() : "INTERNAL_ERROR";
        long seconds = exception instanceof TelegramApiException api ? api.retryAfterSeconds() : 10;
        if (Set.of("NETWORK_ERROR", "API_ERROR", "INTERNAL_ERROR").contains(status)) {
            seconds = Math.max(seconds, Math.min(900, 5L << Math.min(failures, 8)));
        }
        long retryAt = clock.millis() + seconds * 1000;
        retries.put(channel.getId(), new RetryState(retryAt, failures, status, channel.getUpdatedAt()));
        if ("BUSY".equals(status)) return;
        boolean shouldLog = previous == null || !status.equals(previous.status()) || failures % 5 == 0;
        if (shouldLog && exception instanceof TelegramApiException) {
            log.warn("Telegram 渠道轮询暂停，渠道：{}，状态：{}，下次重试：{}", channel.getId(), status,
                    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(retryAt), ZoneOffset.UTC));
        } else if (shouldLog) {
            log.error("Telegram 渠道轮询内部异常，渠道：{}，{} 秒后重试", channel.getId(), seconds, exception);
        }
    }

    @PreDestroy
    public void stop() { stopping.set(true); }
}
