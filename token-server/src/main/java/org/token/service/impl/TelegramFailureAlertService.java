package org.token.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.token.repository.system.NotificationChannelConfigRepository;
import org.token.model.entity.NotificationChannelConfig;
import org.token.service.NotificationService;
import org.token.service.TranslationService;
import org.token.tools.media.SundryUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Telegram 失败告警；数据库条件更新保证同一渠道 24 小时内最多告警一次。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramFailureAlertService {

    private final NotificationChannelConfigRepository channelRepository;
    private final NotificationService notificationService;
    private final TranslationService translationService;

    public void notifyFailure(NotificationChannelConfig channel) {
        if (channel == null || channel.getId() == null) {
            return;
        }
        LocalDateTime now = SundryUtils.getTime();
        try {
            int claimed = channelRepository.claimFailureAlert(channel.getId(),now,now.minusHours(24));
            if (claimed != 1) {
                return;
            }
            notificationService.notifyTelegramFailure(channel.getName(),channel.getId());
        } catch (Exception exception) {
            // 告警链路不能反向影响原通知请求或后台轮询线程。
            log.warn("Telegram 渠道失败告警发送失败，渠道 ID：{}", channel.getId(), exception);
        }
    }

}
