package org.token.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.token.repository.dao.NotificationChannelConfigMapper;
import org.token.model.entity.NotificationChannelConfig;
import org.token.service.NotificationService;
import org.token.service.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramFailureAlertServiceTest {

    private NotificationChannelConfigMapper mapper;
    private NotificationService notificationService;
    private TranslationService translationService;
    private TelegramFailureAlertService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace("org.exp.test.NotificationChannelConfigMapper");
        TableInfoHelper.initTableInfo(assistant, NotificationChannelConfig.class);
        mapper = mock(NotificationChannelConfigMapper.class);
        notificationService = mock(NotificationService.class);
        translationService = mock(TranslationService.class);
        service = new TelegramFailureAlertService(new org.token.repository.system.NotificationChannelConfigRepository(mapper), notificationService, translationService);
    }

    @Test
    void firstFailureClaimsWindowAndNotifiesSuperAdmins() {
        NotificationChannelConfig channel = channel();
        when(mapper.claimFailureAlert(any(), any(), any())).thenReturn(1);

        service.notifyFailure(channel);

        verify(notificationService).notifyTelegramFailure("<测试>",7L);
        org.mockito.Mockito.verifyNoInteractions(translationService);
    }

    @Test
    void activeRateLimitWindowDoesNotSendAnotherAlert() {
        when(mapper.claimFailureAlert(any(), any(), any())).thenReturn(0);

        service.notifyFailure(channel());

        verify(notificationService, never()).notifyTelegramFailure(any(), any());
    }

    private NotificationChannelConfig channel() {
        NotificationChannelConfig channel = new NotificationChannelConfig();
        channel.setId(7L);
        channel.setName("<测试>");
        return channel;
    }
}
