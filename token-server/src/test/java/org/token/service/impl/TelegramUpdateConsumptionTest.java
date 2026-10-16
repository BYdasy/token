package org.token.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.dao.NotificationChannelConfigMapper;
import org.token.repository.dao.NotificationTemplateMapper;
import org.token.model.entity.NotificationChannelConfig;
import org.token.service.LanguageService;
import org.token.service.TranslationService;
import org.token.tools.security.SensitiveConfigCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 自动回复聊天 ID 的消费隔离验证；不访问 Telegram、数据库或真实密钥。 */
class TelegramUpdateConsumptionTest {
    private static final String TOKEN = "123:isolated";
    private final ObjectMapper json = new ObjectMapper();
    private NotificationChannelConfigMapper mapper;
    private TelegramBotClient bot;
    private TelegramFailureAlertService alerts;
    private NotificationConfigServiceImpl service;
    private NotificationChannelConfig channel;
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
    private ch.qos.logback.classic.Logger logger;
    private boolean additive;

    @BeforeEach
    void setup() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test");
        assistant.setCurrentNamespace(NotificationChannelConfigMapper.class.getName());
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, NotificationChannelConfig.class);
        mapper = mock(NotificationChannelConfigMapper.class); bot = mock(TelegramBotClient.class); alerts = mock(TelegramFailureAlertService.class);
        SensitiveConfigCipher cipher = mock(SensitiveConfigCipher.class);
        when(cipher.encrypt(anyString())).thenAnswer(call -> call.getArgument(0));
        when(cipher.decrypt(anyString())).thenAnswer(call -> call.getArgument(0));
        channel = new NotificationChannelConfig(); channel.setId(7L); channel.setChannelType("TELEGRAM"); channel.setEnabled(1);
        channel.setEncryptedConfig("{\"botToken\":\"123:isolated\",\"lastUpdateOffset\":4,\"chatIds\":\"original\"}");
        when(mapper.selectById(7L)).thenReturn(channel);
        when(mapper.replaceConfig(any(),any(),any(),any())).thenReturn(1);
        TranslationService translations = mock(TranslationService.class);
        when(translations.getTranslate("server.notification.telegram.currentChatId", "zh")).thenReturn("聊天 ID：{{chatId}}");
        when(bot.sendMessage(anyString(), anyString(), anyString())).thenAnswer(call -> Map.of("ok", true));
        when(bot.pollingStatus(TOKEN)).thenReturn(new TelegramBotClient.PollStatus("READY", "Token_bot", null, null, null, 0));
        service = new NotificationConfigServiceImpl(new org.token.repository.system.NotificationChannelConfigRepository(mapper), new org.token.repository.system.NotificationTemplateRepository(mock(NotificationTemplateMapper.class)), cipher, json, translations, org.mockito.Mockito.mock(org.token.repository.system.TranslationRepository.class), mock(LanguageService.class), bot, new TelegramChannelLockRegistry(), alerts);
        logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(NotificationConfigServiceImpl.class);
        additive = logger.isAdditive(); logs.start(); logger.setAdditive(false); logger.addAppender(logs);
    }

    @AfterEach void restoreLogs() { logger.detachAppender(logs); logger.setAdditive(additive); logs.stop(); }

    @Test
    void forbiddenReplyDoesNotRepeatEarlierSuccessOrBlockLaterUpdates() throws Exception {
        when(bot.getUpdates(TOKEN, 4)).thenAnswer(call -> response(update(4, "first"), update(5, "blocked"), update(6, "later")));
        when(bot.getUpdates(TOKEN, 7)).thenAnswer(call -> response());
        when(bot.sendMessage(eq(TOKEN), eq("blocked"), anyString())).thenThrow(TelegramApiException.fromResponse(403, 0));
        assertEquals(3, service.telegramUpdates(7L, "zh").size());
        assertEquals(7, config().get("lastUpdateOffset").asInt());
        assertTrue(service.telegramUpdates(7L, "zh").isEmpty());
        verify(bot, times(1)).sendMessage(eq(TOKEN), eq("first"), anyString());
        verify(bot, times(1)).sendMessage(eq(TOKEN), eq("blocked"), anyString());
        verify(bot, times(1)).sendMessage(eq(TOKEN), eq("later"), anyString());
        verify(alerts, times(1)).notifyFailure(channel);
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN));
    }

    @Test
    void cursorIsPersistedBeforeReplyAndInterruptedReplyIsNotSentAgain() throws Exception {
        when(bot.getUpdates(TOKEN, 4)).thenAnswer(call -> response(update(4, "temporary")));
        when(bot.getUpdates(TOKEN, 5)).thenAnswer(call -> response(update(5, "new-message")));
        when(bot.sendMessage(eq(TOKEN), eq("temporary"), anyString())).thenAnswer(call -> {
            assertEquals(5, config().get("lastUpdateOffset").asInt());
            throw new TelegramApiException("NETWORK_ERROR", 30);
        });
        service.telegramUpdates(7L, "zh");
        service.telegramUpdates(7L, "zh");
        verify(bot).pausePolling(eq(TOKEN), any(TelegramApiException.class));
        verify(bot, times(1)).sendMessage(eq(TOKEN), eq("temporary"), anyString());
        verify(bot, times(1)).sendMessage(eq(TOKEN), eq("new-message"), anyString());
        assertEquals(6, config().get("lastUpdateOffset").asInt());
    }

    @Test
    void rateLimitPausesRemainingRepliesWithoutBlockingConsumption() throws Exception {
        when(bot.getUpdates(TOKEN, 4)).thenAnswer(call -> response(update(4, "limited"), update(5, "later")));
        when(bot.sendMessage(eq(TOKEN), eq("limited"), anyString())).thenThrow(new TelegramApiException("RATE_LIMITED", 90));
        service.telegramUpdates(7L, "zh");
        verify(bot, never()).sendMessage(eq(TOKEN), eq("later"), anyString());
        verify(bot).pausePolling(eq(TOKEN), any(TelegramApiException.class));
        assertEquals(6, config().get("lastUpdateOffset").asInt());
    }

    @Test
    void concurrentConfigEditIsPreservedWhileCursorAdvances() throws Exception {
        when(bot.getUpdates(TOKEN, 4)).thenAnswer(call -> response(update(4, "chat")));
        AtomicInteger attempts = new AtomicInteger();
        when(mapper.replaceConfig(any(),any(),any(),any())).thenAnswer(call -> {
            assertEquals(channel.getId(),call.getArgument(0));
            assertEquals(channel.getEncryptedConfig(),call.getArgument(1));
            assertEquals(5,json.readTree((String)call.getArgument(2)).get("lastUpdateOffset").asInt());
            assertNotNull(call.getArgument(3));
            if (attempts.getAndIncrement() == 0) {
                channel.setEncryptedConfig("{\"botToken\":\"123:isolated\",\"lastUpdateOffset\":4,\"chatIds\":\"new-recipient\"}"); return 0;
            }
            return 1;
        });
        service.telegramUpdates(7L, "zh");
        assertEquals("new-recipient", config().get("chatIds").asText());
        assertEquals(5, config().get("lastUpdateOffset").asInt());
        verify(mapper, times(2)).replaceConfig(any(),any(),any(),any());
        verify(mapper, never()).updateById(any(NotificationChannelConfig.class));
    }

    @Test
    void tokenChangedDuringPollStopsOldBatchWithoutOverwritingNewConfiguration() throws Exception {
        when(bot.getUpdates(TOKEN, 4)).thenAnswer(call -> {
            channel.setEncryptedConfig("{\"botToken\":\"456:replacement\",\"lastUpdateOffset\":0}");
            return response(update(4, "old-chat"), update(5, "old-chat2"));
        });
        assertTrue(service.telegramUpdates(7L, "zh").isEmpty());
        verify(bot, never()).sendMessage(anyString(), anyString(), anyString());
        verify(mapper, never()).replaceConfig(any(),any(),any(),any());
        assertEquals("456:replacement", config().get("botToken").asText());
    }

    @Test
    void cursorCannotMoveBackAfterConcurrentProgress() throws Exception {
        when(bot.getUpdates(TOKEN, 4)).thenAnswer(call -> {
            channel.setEncryptedConfig("{\"botToken\":\"123:isolated\",\"lastUpdateOffset\":9}");
            return response(update(4, "old-chat"));
        });
        service.telegramUpdates(7L, "zh");
        assertEquals(9, config().get("lastUpdateOffset").asInt());
        verify(bot, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @CsvSource({
            "private, /start, true",
            "private, hello, false",
            "private, /starter, false",
            "private, please /start, false",
            "private, /start@Token_bot, false",
            "private, /start extra, false",
            "group, /start, false",
            "group, @Token_bot /start, true",
            "supergroup, @token_BOT /start, true",
            "group, /start@Token_bot, true",
            "supergroup, /start@TOKEN_BOT, true",
            "group, @Another_bot /start, false",
            "group, /start@Another_bot, false",
            "group, @Token_bot_extra /start, false",
            "group, /start@Token_bot_extra, false",
            "group, @Token_bot hello /start, false",
            "group, @Token_bot /starter, false",
            "group, hello @Token_bot /start, false",
            "group, @Token_bot /start extra, false",
            "group, @Token_bot /START, false",
            "channel, /start@Token_bot, false",
            "unknown, /start, false"
    })
    void onlyExplicitStartCommandsReplyAndOtherUpdatesStillAdvance(String chatType, String text, boolean expectedReply) throws Exception {
        when(bot.getUpdates(TOKEN, 4)).thenAnswer(call -> response(Map.of("update_id", 4,
                "message", Map.of("text", text, "chat", Map.of("id", "123", "type", chatType)))));

        assertEquals("123", service.telegramUpdates(7L, "zh").get(0).get("id"));

        verify(bot, times(expectedReply ? 1 : 0)).sendMessage(TOKEN, "123", "聊天 ID：123");
        assertEquals(5, config().get("lastUpdateOffset").asInt());
        verifyNoInteractions(alerts);
    }

    @Test
    void groupCommandRequiresVerifiedCurrentBotIdentity() throws Exception {
        when(bot.pollingStatus(TOKEN)).thenReturn(new TelegramBotClient.PollStatus("INITIALIZING", "Token_bot", null, null, null, 0));
        when(bot.getUpdates(TOKEN, 4)).thenAnswer(call -> response(Map.of("update_id", 4,
                "message", Map.of("text", "/start@Token_bot", "chat", Map.of("id", "123", "type", "group")))));
        service.telegramUpdates(7L, "zh");
        verify(bot, never()).sendMessage(anyString(), anyString(), anyString());
        assertEquals(5, config().get("lastUpdateOffset").asInt());
    }

    @Test
    void photosCaptionsAndChannelPostsNeverReply() throws Exception {
        Map<String, Object> group = Map.of("id", "123", "type", "group");
        when(bot.getUpdates(TOKEN, 4)).thenAnswer(call -> response(
                Map.of("update_id", 4, "message", Map.of("text", "hello", "from", Map.of("is_bot", true), "chat", group)),
                Map.of("update_id", 5, "message", Map.of("caption", "/start@Token_bot", "photo", List.of(Map.of("file_id", "isolated")), "chat", group)),
                Map.of("update_id", 6, "channel_post", Map.of("text", "/start@Token_bot", "chat", Map.of("id", "456", "type", "channel"))),
                Map.of("update_id", 7, "edited_message", Map.of("text", "/start@Token_bot", "chat", group))));
        assertEquals(2, service.telegramUpdates(7L, "zh").size());
        verify(bot, never()).sendMessage(anyString(), anyString(), anyString());
        assertEquals(8, config().get("lastUpdateOffset").asInt());
    }

    @Test
    void anonymousGroupAdministratorCanSendExplicitCommand() throws Exception {
        when(bot.getUpdates(TOKEN, 4)).thenAnswer(call -> response(Map.of("update_id", 4,
                "message", Map.of("text", "/start@Token_bot",
                        "from", Map.of("is_bot", true, "username", "GroupAnonymousBot"),
                        "sender_chat", Map.of("id", "-123", "type", "supergroup"),
                        "chat", Map.of("id", "-123", "type", "supergroup")))));
        service.telegramUpdates(7L, "zh");
        verify(bot).sendMessage(TOKEN, "-123", "聊天 ID：-123");
        assertEquals(5, config().get("lastUpdateOffset").asInt());
    }

    private com.fasterxml.jackson.databind.JsonNode config() throws Exception { return json.readTree(channel.getEncryptedConfig()); }
    private Map<String, Object> update(long id, String chat) { return Map.of("update_id", id, "message", Map.of("text", "/start", "chat", Map.of("id", chat, "type", "private"))); }
    @SafeVarargs private final Map<String, Object> response(Map<String, Object>... updates) { return Map.of("ok", true, "result", List.of(updates)); }
}
