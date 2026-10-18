package org.token.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.dao.NotificationChannelConfigMapper;
import org.token.repository.dao.NotificationTemplateMapper;
import org.token.model.entity.NotificationChannelConfig;
import org.token.model.entity.NotificationTemplate;
import org.token.model.request.notification.NotificationTemplateSaveRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.token.exception.BusinessException;
import org.token.service.TranslationService;
import org.token.service.LanguageService;
import org.token.tools.security.SensitiveConfigCipher;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class NotificationConfigServiceImplTest {

    @Test void strictTelegramDispatchRejectsChangedChannelTypeBeforeDecryptingOrSending() {
        var channels = mock(org.token.repository.system.NotificationChannelConfigRepository.class);
        var cipher = mock(SensitiveConfigCipher.class);
        var client = mock(TelegramBotClient.class);
        var channel = new NotificationChannelConfig(); channel.setId(8L); channel.setChannelType("EMAIL"); channel.setEnabled(1);
        when(channels.getById(8L)).thenReturn(channel);
        var service = new NotificationConfigServiceImpl(channels, mock(org.token.repository.system.NotificationTemplateRepository.class), cipher,
                new ObjectMapper(), mock(TranslationService.class), mock(org.token.repository.system.TranslationRepository.class),
                mock(LanguageService.class), client, new TelegramChannelLockRegistry(), mock(TelegramFailureAlertService.class));
        assertThrows(BusinessException.class, () -> service.sendTelegramExternal(8L, "Title", "Body", "-100123", "en"));
        org.mockito.Mockito.verifyNoInteractions(cipher, client);
    }

    @org.junit.jupiter.api.BeforeEach
    void initializeMapperMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test");
        assistant.setCurrentNamespace(NotificationChannelConfigMapper.class.getName());
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, NotificationChannelConfig.class);
    }

    @Test
    void enabledChannelMustBeDisabledBeforeDeletion() {
        NotificationChannelConfigMapper channelMapper = mock(NotificationChannelConfigMapper.class);
        NotificationChannelConfig channel = new NotificationChannelConfig();
        channel.setId(3L);
        channel.setEnabled(1);
        when(channelMapper.selectById(3L)).thenReturn(channel);
        NotificationConfigServiceImpl service = new NotificationConfigServiceImpl(new org.token.repository.system.NotificationChannelConfigRepository(channelMapper), new org.token.repository.system.NotificationTemplateRepository(mock(NotificationTemplateMapper.class)), mock(SensitiveConfigCipher.class), new ObjectMapper(), mock(TranslationService.class), org.mockito.Mockito.mock(org.token.repository.system.TranslationRepository.class), mock(LanguageService.class), mock(TelegramBotClient.class), new TelegramChannelLockRegistry(), mock(TelegramFailureAlertService.class));

        assertThrows(BusinessException.class, () -> service.deleteChannel(3L));
        verify(channelMapper, never()).deleteById(3L);
    }

    @Test
    void newInternalTemplateReceivesGeneratedCode() {
        NotificationChannelConfigMapper channelMapper = mock(NotificationChannelConfigMapper.class);
        NotificationTemplateMapper templateMapper = mock(NotificationTemplateMapper.class);
        AtomicReference<NotificationTemplate> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            NotificationTemplate template = invocation.getArgument(0);
            template.setId(99L);
            stored.set(template);
            return 1;
        }).when(templateMapper).insert(any(NotificationTemplate.class));
        when(templateMapper.selectById(99L)).thenAnswer(ignored -> stored.get());
        LanguageService languages = mock(LanguageService.class);
        org.token.model.entity.Language english = new org.token.model.entity.Language();
        english.setLang("en");
        when(languages.listLanguages(true)).thenReturn(List.of(english));
        NotificationConfigServiceImpl service = new NotificationConfigServiceImpl(new org.token.repository.system.NotificationChannelConfigRepository(channelMapper), new org.token.repository.system.NotificationTemplateRepository(templateMapper), mock(SensitiveConfigCipher.class), new ObjectMapper(), mock(TranslationService.class), org.mockito.Mockito.mock(org.token.repository.system.TranslationRepository.class), languages, mock(TelegramBotClient.class), new TelegramChannelLockRegistry(), mock(TelegramFailureAlertService.class));

        org.token.model.response.NotificationTemplateView result = service.saveTemplate(new NotificationTemplateSaveRequest(
                null, null, "站内通知", "INTERNAL", "标题", "<p>正文</p>", 1,
                List.of(new org.token.model.request.notification.NotificationTemplateLocalizationRequest(
                        "en", "Title", "<p>Body</p>"))));

        assertTrue(result.code().matches("N[A-Z0-9]{10}"));
        assertEquals("T99", result.shortCode());
        assertEquals("INTERNAL", result.channelType());
        assertEquals(3, result.translationType());
    }

    @Test
    void dingTalkHttp200StillRequiresSuccessfulBusinessCode() {
        assertDoesNotThrow(() -> NotificationConfigServiceImpl.requireDingTalkSuccess(Map.of("errcode", 0)));
        assertThrows(BusinessException.class,
                () -> NotificationConfigServiceImpl.requireDingTalkSuccess(Map.of("errcode", 310000)));
        assertThrows(BusinessException.class,
                () -> NotificationConfigServiceImpl.requireDingTalkSuccess(null));
    }

    @Test
    void configuredDingTalkKeywordIsAddedToMessage() {
        assertEquals(
                "EXP通知\n标题\n正文",
                NotificationConfigServiceImpl.dingTalkContent(" EXP通知 ", "标题", "正文")
        );
    }

    @Test
    void existingDingTalkKeywordIsNotRepeated() {
        assertEquals(
                "标题\n正文已包含EXP通知",
                NotificationConfigServiceImpl.dingTalkContent("EXP通知", "标题", "正文已包含EXP通知")
        );
    }

    @Test
    void masksDingTalkWebhookBecauseItContainsAccessToken() {
        assertTrue(NotificationConfigServiceImpl.isSensitiveKey("webhook"));
    }

    @Test
    void splitsAndDeduplicatesMultipleTelegramChatIds() {
        assertEquals(
                List.of("-100123", "200456", "300789"),
                NotificationConfigServiceImpl.splitRecipients("-100123, 200456；300789\n-100123")
        );
    }

    @Test
    void telegramHttp200StillRequiresSuccessfulBusinessStatus() {
        assertDoesNotThrow(() -> NotificationConfigServiceImpl.requireTelegramSuccess(Map.of("ok", true)));
        assertThrows(BusinessException.class,
                () -> NotificationConfigServiceImpl.requireTelegramSuccess(Map.of("ok", false)));
        assertThrows(BusinessException.class,
                () -> NotificationConfigServiceImpl.requireTelegramSuccess(null));
    }

    @ParameterizedTest
    @MethodSource("telegramMessages")
    void sendsTelegramNotificationToEveryDistinctChatId(String title, String body, String expectedMessage) throws Exception {
        NotificationChannelConfigMapper channelMapper = mock(NotificationChannelConfigMapper.class);
        NotificationTemplateMapper templateMapper = mock(NotificationTemplateMapper.class);
        SensitiveConfigCipher cipher = mock(SensitiveConfigCipher.class);
        TranslationService translationService = mock(TranslationService.class);
        TelegramBotClient telegramBotClient = mock(TelegramBotClient.class);
        NotificationConfigServiceImpl service = new NotificationConfigServiceImpl(new org.token.repository.system.NotificationChannelConfigRepository(channelMapper), new org.token.repository.system.NotificationTemplateRepository(templateMapper), cipher, new ObjectMapper(), translationService, org.mockito.Mockito.mock(org.token.repository.system.TranslationRepository.class), mock(LanguageService.class), telegramBotClient, new TelegramChannelLockRegistry(), mock(TelegramFailureAlertService.class));

        NotificationChannelConfig channel = new NotificationChannelConfig();
        channel.setId(8L);
        channel.setChannelType("TELEGRAM");
        channel.setEnabled(1);
        channel.setEncryptedConfig("encrypted");
        when(channelMapper.selectById(8L)).thenReturn(channel);
        when(cipher.decrypt("encrypted")).thenReturn("{\"botToken\":\"123456:ABC_def\"}");
        org.mockito.Mockito.doReturn(Map.of("ok", true))
                .when(telegramBotClient).sendMessage(any(), any(), any());

        service.sendExternal(8L, title, body, "-100123, 200456；-100123", "zh");

        verify(telegramBotClient).sendMessage("123456:ABC_def", "-100123", expectedMessage);
        verify(telegramBotClient).sendMessage("123456:ABC_def", "200456", expectedMessage);
        verifyNoMoreInteractions(telegramBotClient);
    }

    static Stream<Arguments> telegramMessages() {
        return Stream.of(
                Arguments.of("标题", "正文", "标题\n正文"),
                Arguments.of("标题 <确认> & 进度", "<strong>正文</strong>",
                        "标题 &lt;确认&gt; &amp; 进度\n<strong>正文</strong>"),
                Arguments.of("标题", "<p>第一段</p><p>第二段</p>", "标题\n第一段\n第二段"),
                Arguments.of("提款待审核提醒", "用户: <code>alice</code>  \r\n提款订单: <code>WD1</code> \r\n已等待审核超过 <b>30</b> 分钟。",
                        "提款待审核提醒\n用户: <code>alice</code>\n提款订单: <code>WD1</code>\n已等待审核超过 <b>30</b> 分钟。"));
    }

    @Test
    void manualTelegramPollingDiscoversPhotoChatWithoutReplyAndPersistsNextOffset() throws Exception {
        NotificationChannelConfigMapper channelMapper = mock(NotificationChannelConfigMapper.class);
        NotificationTemplateMapper templateMapper = mock(NotificationTemplateMapper.class);
        SensitiveConfigCipher cipher = mock(SensitiveConfigCipher.class);
        TranslationService translationService = mock(TranslationService.class);
        TelegramBotClient telegramBotClient = mock(TelegramBotClient.class);
        NotificationConfigServiceImpl service = new NotificationConfigServiceImpl(new org.token.repository.system.NotificationChannelConfigRepository(channelMapper), new org.token.repository.system.NotificationTemplateRepository(templateMapper), cipher, new ObjectMapper(), translationService, org.mockito.Mockito.mock(org.token.repository.system.TranslationRepository.class), mock(LanguageService.class), telegramBotClient, new TelegramChannelLockRegistry(), mock(TelegramFailureAlertService.class));

        NotificationChannelConfig channel = new NotificationChannelConfig();
        channel.setId(7L);
        channel.setChannelType("TELEGRAM");
        channel.setEnabled(1);
        channel.setEncryptedConfig("encrypted");
        when(channelMapper.selectById(7L)).thenReturn(channel);
        when(cipher.decrypt(any())).thenReturn("{\"botToken\":\"token\",\"lastUpdateOffset\":4}");
        when(cipher.encrypt(any())).thenReturn("updated");
        when(channelMapper.replaceConfig(any(),any(),any(),any())).thenReturn(1);
        when(translationService.getTranslate("server.notification.telegram.currentChatId", "zh"))
                .thenReturn("当前聊天 ID：{{chatId}}");
        org.mockito.Mockito.doReturn(Map.of(
                "ok", true,
                "result", List.of(Map.of(
                        "update_id", 12,
                        "message", Map.of(
                                "photo", List.of(Map.of("file_id", "photo-file")),
                                "chat", Map.of("id", -100123L, "type", "group", "title", "测试群"))))))
                .when(telegramBotClient).getUpdates("token", 4L);
        org.mockito.Mockito.doReturn(Map.of("ok", true))
                .when(telegramBotClient).sendMessage("token", "-100123", "当前聊天 ID：-100123");

        List<Map<String, Object>> result = service.telegramUpdates(7L, "zh");

        assertEquals("-100123", result.get(0).get("id"));
        verify(telegramBotClient, never()).sendMessage(any(), any(), any());
        ArgumentCaptor<String> encryptedJson = ArgumentCaptor.forClass(String.class);
        verify(cipher).encrypt(encryptedJson.capture());
        assertEquals(13L, new ObjectMapper().readTree(encryptedJson.getValue()).get("lastUpdateOffset").asLong());
    }
}
