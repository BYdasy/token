package org.token.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.notification.NotificationConfigPageRequest;
import org.token.model.response.NotificationChannelOptionView;
import lombok.RequiredArgsConstructor;
import org.token.repository.system.NotificationChannelConfigRepository;
import org.token.repository.system.NotificationTemplateRepository;
import org.token.model.entity.Language;
import org.token.model.entity.NotificationChannelConfig;
import org.token.model.entity.NotificationTemplate;
import org.token.model.entity.Translation;
import org.token.model.request.notification.ChannelConfigSaveRequest;
import org.token.model.request.notification.NotificationTemplateLocalizationRequest;
import org.token.model.request.notification.NotificationTemplateSaveRequest;
import org.token.model.response.NotificationChannelView;
import org.token.model.response.GoogleOAuthStartView;
import org.token.model.response.NotificationTemplateLanguageView;
import org.token.model.response.NotificationTemplateLocalizationView;
import org.token.model.response.NotificationTemplateView;
import org.token.exception.BusinessException;
import org.token.service.LanguageService;
import org.token.service.NotificationConfigService;
import org.token.service.TranslationService;
import org.token.tools.security.SensitiveConfigCipher;
import org.token.util.EnglishTranslationValidator;
import org.token.tools.media.SundryUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.security.SecureRandom;

/** 外部通知渠道与通知模板管理。 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class NotificationConfigServiceImpl implements NotificationConfigService {

    private static final int EMAIL_TEMPLATE_TRANSLATION_TYPE = 2;
    private static final int INTERNAL_TEMPLATE_TRANSLATION_TYPE = 3;
    private static final int MESSAGE_TEMPLATE_TRANSLATION_TYPE = 6;
    private static final String TEMPLATE_TRANSLATION_KEY_PREFIX = "notification.template.";
    private static final SecureRandom TEMPLATE_CODE_RANDOM = new SecureRandom();

    private static final Set<String> CHANNEL_TYPES = Set.of("EMAIL", "TELEGRAM", "DINGTALK");
    private static final Set<String> TEMPLATE_CHANNEL_TYPES = Set.of(
            "EMAIL", "TELEGRAM", "DINGTALK", "INTERNAL"
    );
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "token", "bottoken", "secret", "clientsecret", "refreshtoken", "accesstoken", "webhook"
    );

    private final NotificationChannelConfigRepository channelRepository;
    private final NotificationTemplateRepository templateRepository;
    private final SensitiveConfigCipher cipher;
    private final ObjectMapper objectMapper;
    private final TranslationService translationService;
    private final org.token.repository.system.TranslationRepository translationRepository;
    private final LanguageService languageService;
    private final TelegramBotClient telegramBotClient;
    private final TelegramChannelLockRegistry telegramChannelLocks;
    private final TelegramFailureAlertService telegramFailureAlertService;

    private final RestClient restClient = buildRestClient();

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(15_000);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public List<NotificationChannelView> channels() {
        List<NotificationChannelConfig> records = channelRepository.findAllOrdered();
        return records == null ? List.of() : records.stream().map(this::toView).toList();
    }

    @Override
    public Page<NotificationChannelView> channelPage(NotificationConfigPageRequest request) {
        var page = channelRepository.findPage(request.current(), request.size(), request.keyword(), request.channelType());
        return new Page<NotificationChannelView>(page.getCurrent(), page.getSize(), page.getTotal())
                .setRecords(page.getRecords().stream().map(this::toView).toList());
    }

    @Override
    public List<NotificationChannelOptionView> channelOptions() {
        return channelRepository.findChannelOptions().stream()
                .map(channel -> new NotificationChannelOptionView(channel.getId(), channel.getName(), channel.getChannelType())).toList();
    }

    @Override
    public NotificationChannelView saveChannel(ChannelConfigSaveRequest request) {
        if (request == null) {
            throw new BusinessException("server.notification.channel.required");
        }
        String type = normalizeChannelType(request.channelType());
        NotificationChannelConfig old = request.id() == null ? null : requireChannel(request.id());
        Map<String, Object> merged = old == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(readConfig(old));
        if (request.config() != null) {
            request.config().forEach((key, value) -> {
                if (!isMasked(value) && !"pollingStatus".equals(key) && !"lastUpdateOffset".equals(key)) {
                    merged.put(key, value);
                }
            });
        }
        validateConfig(type, merged);
        if ("TELEGRAM".equals(type)) {
            String token = required(merged, "botToken");
            List<NotificationChannelConfig> others = channelRepository.telegram(false);
            if (others != null && others.stream().anyMatch(other -> !java.util.Objects.equals(other.getId(), request.id())
                    && token.equals(text(readConfig(other).get("botToken"))))) {
                throw new BusinessException("server.notification.telegram.duplicateBot");
            }
            if (old != null && !token.equals(text(readConfig(old).get("botToken")))) merged.remove("lastUpdateOffset");
            if (old != null) telegramBotClient.resetPolling(text(readConfig(old).get("botToken")));
            telegramBotClient.resetPolling(token);
        }

        NotificationChannelConfig record = new NotificationChannelConfig();
        record.setId(request.id());
        record.setName(request.name().trim());
        record.setChannelType(type);
        record.setEnabled(request.enabled() != null && request.enabled() == 0 ? 0 : 1);
        record.setEncryptedConfig(cipher.encrypt(writeJson(merged)));
        record.setDefaultRecipients(trimToNull(request.defaultRecipients()));
        record.setUpdatedAt(SundryUtils.getTime());
        if (old == null) {
            record.setCreatedAt(record.getUpdatedAt());
            if (!channelRepository.save(record)) {
                throw new BusinessException("server.notification.channel.saveFailed");
            }
        } else if (!channelRepository.updateById(record)) {
            throw new BusinessException("server.notification.channel.saveFailed");
        }
        return toView(requireChannel(record.getId()));
    }

    @Override
    public void deleteChannel(Long id) {
        NotificationChannelConfig record = requireChannel(id);
        if (Integer.valueOf(1).equals(record.getEnabled())) {
            throw new BusinessException("server.notification.channel.disableBeforeDelete");
        }
        if (!channelRepository.removeById(id)) {
            throw new BusinessException("server.notification.channel.deleteFailed");
        }
        if ("TELEGRAM".equals(record.getChannelType())) telegramBotClient.resetPolling(text(readConfig(record).get("botToken")));
    }

    @Override
    public void testChannel(Long id, String recipients, String language) {
        sendExternal(id,
                translated("server.notification.channel.test.title", language),
                translated("server.notification.channel.test.content", language),
                recipients, language);
    }

    @Override
    public List<Map<String, Object>> telegramUpdates(Long id, String language) {
        return telegramChannelLocks.execute(id, () -> {
            NotificationChannelConfig record = requireTelegramChannel(id, false);
            try {
                return loadTelegramUpdates(record, language);
            } catch (BusinessException e) {
                if (!(e instanceof TelegramApiException api) || !"BUSY".equals(api.status())) telegramFailureAlertService.notifyFailure(record);
                throw e;
            } catch (Exception e) {
                telegramFailureAlertService.notifyFailure(record);
                log.error("手动获取 Telegram 更新内部异常，渠道：{}", record.getId(), e);
                throw new BusinessException("server.notification.external.sendFailed");
            }
        });
    }

    @Override
    public boolean pollTelegramChannel(Long id) {
        return telegramChannelLocks.tryExecute(id, () -> {
            NotificationChannelConfig record = requireTelegramChannel(id, true);
            try {
                loadTelegramUpdates(record, null);
            } catch (RuntimeException exception) {
                if (!(exception instanceof TelegramApiException api) || !"BUSY".equals(api.status())) telegramFailureAlertService.notifyFailure(record);
                throw exception;
            }
        });
    }

    @Override
    public NotificationChannelView inspectTelegram(Long id) {
        return telegramChannelLocks.execute(id, () -> {
            NotificationChannelConfig record = requireTelegramChannel(id, false);
            String token = required(readConfig(record), "botToken");
            return telegramChannelLocks.executeBot(token, () -> {
                TelegramBotClient.PollStatus previous = telegramBotClient.pollingStatus(token);
                // Telegram 的 retry_after 必须遵守，人工诊断也不能绕过限流。
                if (!"RATE_LIMITED".equals(previous.status()) || previous.retryAt() == null
                        || !java.time.Instant.now().isBefore(previous.retryAt())) telegramBotClient.resetPolling(token);
                try { telegramBotClient.inspectPolling(token); }
                catch (TelegramApiException exception) { telegramFailureAlertService.notifyFailure(record); }
                // 人工诊断完成后让调度器重新读取状态，不修改 Bot 配置、接收人或消费游标。
                channelRepository.touch(id,SundryUtils.getTime());
                return toView(record);
            });
        });
    }

    private List<Map<String, Object>> loadTelegramUpdates(NotificationChannelConfig record, String language) {
        String token = required(readConfig(record), "botToken");
        return telegramChannelLocks.executeBot(token, () -> {
            // 取得跨实例机器人锁之后重读 offset，避免另一实例已消费而本实例仍使用旧快照。
            NotificationChannelConfig latest = requireTelegramChannel(record.getId(), false);
            if (!token.equals(required(readConfig(latest), "botToken"))) return List.of();
            return consumeTelegramUpdates(latest, language);
        });
    }

    private List<Map<String, Object>> consumeTelegramUpdates(NotificationChannelConfig record, String language) {
        String token = required(readConfig(record), "botToken");
        long offset = longValue(readConfig(record).get("lastUpdateOffset"), 0);
        Map<?, ?> response = telegramBotClient.getUpdates(token, offset);
        requireTelegramSuccess(response);
        // getUpdates 已先通过 getMe 校验身份；群组提及只接受这个 Token 的真实用户名。
        TelegramBotClient.PollStatus botStatus = telegramBotClient.pollingStatus(token);
        String botUsername = botStatus != null && "READY".equals(botStatus.status())
                ? botStatus.botUsername() : null;
        Object result = response == null ? null : response.get("result");
        if (!(result instanceof List<?> updates)) {
            return List.of();
        }
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        int failedReplies = 0;
        Set<String> replyFailures = new LinkedHashSet<>();
        RuntimeException unexpectedReplyFailure = null;
        boolean replyPaused = false;
        for (Object update : updates) {
            if (!(update instanceof Map<?, ?> updateMap)) {
                continue;
            }
            long updateId = longValue(updateMap.get("update_id"), -1);
            if (updateId < offset || updateId == Long.MAX_VALUE) continue;
            TelegramUpdateClaim claim = claimTelegramUpdate(record.getId(), token, updateId + 1);
            if (claim == TelegramUpdateClaim.RECONFIGURED) break;
            if (claim == TelegramUpdateClaim.ALREADY_CONSUMED) continue;
            Object message = updateMap.get("message");
            Map<?, ?> messageMap;
            if (message instanceof Map<?, ?> resolvedMessage) {
                messageMap = resolvedMessage;
            } else {
                message = updateMap.get("channel_post");
                if (!(message instanceof Map<?, ?> resolvedChannelPost)) {
                    continue;
                }
                messageMap = resolvedChannelPost;
            }
            Object chatObject = messageMap.get("chat");
            if (!(chatObject instanceof Map<?, ?> chat)) {
                continue;
            }
            Object chatId = chat.get("id");
            if (chatId == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(chatId));
            item.put("type", chat.get("type"));
            item.put("username", chat.get("username"));
            item.put("title", firstNonNull(chat.get("title"), chat.get("first_name")));
            unique.put(String.valueOf(chatId), item);

            if (replyPaused || !updateMap.containsKey("message")
                    || !isTelegramChatIdCommand(messageMap, chat, botUsername)) continue;
            // 聊天 ID 是辅助自动回复，每条更新只尝试一次，不能因失效聊天阻塞整个机器人。
            // 游标已独立保存；普通业务通知仍沿用原 sendExternal 的可靠投递语义。
            try {
                Map<?, ?> sendResponse = telegramBotClient.sendMessage(
                    token,
                    String.valueOf(chatId),
                    TelegramHtmlSanitizer.sanitize(renderTranslated(
                            "server.notification.telegram.currentChatId",
                            language,
                            Map.of("chatId", chatId))));
                requireTelegramSuccess(sendResponse);
            } catch (TelegramApiException exception) {
                failedReplies++; replyFailures.add(exception.status());
                if (Set.of("RATE_LIMITED", "NETWORK_ERROR", "INVALID_TOKEN", "CONFLICT").contains(exception.status())) {
                    replyPaused = true;
                    telegramBotClient.pausePolling(token, exception);
                }
            } catch (RuntimeException exception) {
                failedReplies++; replyFailures.add("INTERNAL_ERROR");
                if (unexpectedReplyFailure == null) unexpectedReplyFailure = exception;
            }
        }
        if (failedReplies > 0) {
            if (unexpectedReplyFailure != null) log.error("Telegram 聊天 ID 自动回复内部异常，渠道：{}，失败 {} 条；消费游标已保存", record.getId(), failedReplies, unexpectedReplyFailure);
            else log.warn("Telegram 聊天 ID 自动回复失败，渠道：{}，失败 {} 条，状态：{}；不重复发送旧回复", record.getId(), failedReplies, replyFailures);
            telegramFailureAlertService.notifyFailure(record);
        }
        return new ArrayList<>(unique.values());
    }

    /** 私信只响应 /start；群组必须明确提及当前机器人，不能对普通聊天或其他机器人的命令回复。 */
    private boolean isTelegramChatIdCommand(Map<?, ?> message, Map<?, ?> chat, String botUsername) {
        if (!(message.get("text") instanceof String text)) return false;
        String command = text.strip();
        if ("private".equals(chat.get("type"))) return "/start".equals(command);
        if (!("group".equals(chat.get("type")) || "supergroup".equals(chat.get("type")))
                || botUsername == null || !botUsername.matches("[A-Za-z0-9_]{5,32}")) return false;
        String mention = "@" + botUsername;
        if (command.startsWith("/start@") && command.substring(6).equalsIgnoreCase(mention)) return true;
        String[] parts = command.split("\\s+", 2);
        return parts.length == 2 && mention.equalsIgnoreCase(parts[0]) && "/start".equals(parts[1]);
    }

    private enum TelegramUpdateClaim { CLAIMED, ALREADY_CONSUMED, RECONFIGURED }

    /** 只推进当前机器人的游标；长轮询期间管理员修改的其他配置必须原样保留。 */
    private TelegramUpdateClaim claimTelegramUpdate(Long channelId, String token, long nextOffset) {
        for (int attempt = 0; attempt < 3; attempt++) {
            NotificationChannelConfig latest = requireTelegramChannel(channelId, false);
            Map<String, Object> config = new LinkedHashMap<>(readConfig(latest));
            if (!token.equals(required(config, "botToken"))) return TelegramUpdateClaim.RECONFIGURED;
            if (longValue(config.get("lastUpdateOffset"), 0) >= nextOffset) return TelegramUpdateClaim.ALREADY_CONSUMED;
            String previous = latest.getEncryptedConfig();
            config.put("lastUpdateOffset", nextOffset);
            String encrypted = cipher.encrypt(writeJson(config));
            if (channelRepository.replaceConfig(channelId,previous,encrypted,SundryUtils.getTime()) == 1) {
                latest.setEncryptedConfig(encrypted);
                return TelegramUpdateClaim.CLAIMED;
            }
        }
        throw new BusinessException("server.notification.channel.saveFailed");
    }

    static void requireTelegramSuccess(Map<?, ?> response) {
        TelegramBotClient.requireSuccess(response);
    }

    @Override
    public GoogleOAuthStartView googleAuthorizeUrl(Long id, String redirectUri) {
        NotificationChannelConfig record = requireEmailOAuthChannel(id);
        Map<String, Object> config = readConfig(record);
        String clientId = required(config, "clientId");
        String finalRedirectUri = StringUtils.hasText(redirectUri)
                ? redirectUri.trim() : required(config, "redirectUri");
        String state = randomState();
        config.put("oauthState", state);
        config.put("oauthStateCreatedAt", System.currentTimeMillis());
        record.setEncryptedConfig(cipher.encrypt(writeJson(config)));
        record.setUpdatedAt(SundryUtils.getTime());
        if (!channelRepository.updateById(record)) {
            throw new BusinessException("server.notification.channel.saveFailed");
        }
        String url = "https://accounts.google.com/o/oauth2/v2/auth?client_id="
                + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(finalRedirectUri)
                + "&response_type=code&scope="
                + urlEncode("https://mail.google.com/")
                + "&access_type=offline&prompt=consent&state=" + urlEncode(state);
        return new GoogleOAuthStartView(url, state);
    }

    @Override
    public NotificationChannelView completeGoogleAuthorization(Long id, String code, String state, String redirectUri) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException("server.notification.google.codeRequired");
        }
        NotificationChannelConfig record = requireEmailOAuthChannel(id);
        Map<String, Object> config = new LinkedHashMap<>(readConfig(record));
        String expectedState = text(config.get("oauthState"));
        long stateCreatedAt = longValue(config.get("oauthStateCreatedAt"), 0);
        if (!StringUtils.hasText(state) || !state.equals(expectedState)
                || stateCreatedAt <= 0 || System.currentTimeMillis() - stateCreatedAt > 10 * 60_000L) {
            throw new BusinessException("server.notification.google.stateInvalid");
        }
        String finalRedirectUri = StringUtils.hasText(redirectUri)
                ? redirectUri.trim() : required(config, "redirectUri");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", required(config, "clientId"));
        form.add("client_secret", required(config, "clientSecret"));
        form.add("code", code.trim());
        form.add("redirect_uri", finalRedirectUri);
        form.add("grant_type", "authorization_code");
        try {
            Map<?, ?> response = restClient.post()
                    .uri("https://oauth2.googleapis.com/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            String refreshToken = response == null ? null : text(response.get("refresh_token"));
            String accessToken = response == null ? null : text(response.get("access_token"));
            if (!StringUtils.hasText(refreshToken) && !StringUtils.hasText(accessToken)) {
                throw new BusinessException("server.notification.google.oauthFailed");
            }
            if (StringUtils.hasText(refreshToken)) {
                config.put("refreshToken", refreshToken);
            }
            if (StringUtils.hasText(accessToken)) {
                config.put("accessToken", accessToken);
            }
            config.remove("oauthState");
            config.remove("oauthStateCreatedAt");
            record.setEncryptedConfig(cipher.encrypt(writeJson(config)));
            record.setUpdatedAt(SundryUtils.getTime());
            if (!channelRepository.updateById(record)) {
                throw new BusinessException("server.notification.channel.saveFailed");
            }
            return toView(requireChannel(id));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("server.notification.google.oauthFailed");
        }
    }

    @Override
    public List<NotificationTemplateView> templates() {
        List<NotificationTemplate> records = templateRepository.findAllOrdered();
        return toTemplateViews(records == null ? List.of() : records);
    }

    @Override
    public Page<NotificationTemplateView> templatePage(NotificationConfigPageRequest request) {
        var page = templateRepository.findPage(request.current(), request.size(), request.keyword(), request.channelType());
        return new Page<NotificationTemplateView>(page.getCurrent(), page.getSize(), page.getTotal())
                .setRecords(toTemplateViews(page.getRecords()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationTemplateView saveTemplate(NotificationTemplateSaveRequest request) {
        if (request == null) {
            throw new BusinessException("server.notification.template.required");
        }
        NotificationTemplate old = request.id() == null ? null : templateRepository.getById(request.id());
        if (request.id() != null && old == null) {
            throw new BusinessException("server.notification.template.notFound");
        }
        String code = old == null ? generateTemplateCode() : old.getCode();
        NotificationTemplate duplicate = templateRepository.findDuplicate(code,request.id());
        if (duplicate != null) {
            throw new BusinessException("server.notification.template.exists");
        }
        String channelType = normalizeTemplateChannelType(request.channelType());
        int translationType = templateTranslationType(channelType);
        Integer oldTranslationType = old == null ? null : templateTranslationType(old.getChannelType());
        List<NormalizedTemplateLocalization> requestedLocalizations = normalizeTemplateLocalizations(request.translations());

        String canonicalTitle = trimToNull(request.titleTemplate());
        String canonicalContent = request.contentTemplate();
        NormalizedTemplateLocalization chinese = requestedLocalizations == null ? null : requestedLocalizations.stream()
                .filter(item -> "zh".equals(item.lang()))
                .findFirst()
                .orElseGet(() -> requestedLocalizations.stream()
                        .filter(item -> item.lang().startsWith("zh-"))
                        .findFirst().orElse(null));
        if (chinese != null && StringUtils.hasText(chinese.content())) {
            canonicalTitle = trimToNull(chinese.title());
            canonicalContent = chinese.content();
        }
        List<NormalizedTemplateLocalization> localizations = requireEnglishLocalization(
                code, oldTranslationType, requestedLocalizations, EnglishTranslationValidator.hasContent(canonicalTitle, 2), templateContentProp(channelType));

        NotificationTemplate record = new NotificationTemplate();
        record.setId(request.id());
        record.setCode(code);
        record.setName(request.name().trim());
        record.setChannelType(channelType);
        record.setTitleTemplate(canonicalTitle);
        record.setContentTemplate(canonicalContent);
        record.setEnabled(request.enabled() != null && request.enabled() == 0 ? 0 : 1);
        record.setUpdatedAt(SundryUtils.getTime());
        if (record.getId() == null) {
            record.setCreatedAt(record.getUpdatedAt());
            if (!templateRepository.save(record)) {
                throw new BusinessException("server.notification.template.saveFailed");
            }
        } else {
            if (!templateRepository.updateById(record)) {
                throw new BusinessException("server.notification.template.saveFailed");
            }
        }
        if (oldTranslationType != null && oldTranslationType != translationType) {
            migrateTemplateTranslations(code, oldTranslationType, translationType);
        }
        if (localizations != null) {
            replaceEnabledTemplateLocalizations(record, translationType, localizations);
        }
        // 中文编辑原文继续镜像到翻译表；英语必备校验与原文保存相互独立。
        upsertTemplateTranslation(templateTitleKey(code), "zh", canonicalTitle,
                translationType, 0, record.getName());
        upsertTemplateTranslation(templateContentKey(code), "zh", canonicalContent,
                translationType, templateContentProp(channelType), record.getName());
        return toTemplateViews(List.of(templateRepository.getById(record.getId()))).get(0);
    }

    @Override
    public List<NotificationTemplateLanguageView> templateLanguages() {
        return enabledTemplateLanguages();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long id) {
        NotificationTemplate record = id == null ? null : templateRepository.getById(id);
        if (record == null) {
            throw new BusinessException("server.notification.template.notFound");
        }
        String titleKey = templateTitleKey(record.getCode());
        String contentKey = templateContentKey(record.getCode());
        translationRepository.deleteTemplateLocalizations(List.of(EMAIL_TEMPLATE_TRANSLATION_TYPE,INTERNAL_TEMPLATE_TRANSLATION_TYPE,MESSAGE_TEMPLATE_TRANSLATION_TYPE),List.of(titleKey,contentKey),null);
        if (!templateRepository.removeById(id)) {
            throw new BusinessException("server.notification.template.notFound");
        }
    }

    @Override
    public NotificationTemplate findEnabledTemplate(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        return templateRepository.findEnabled(code.trim());
    }

    @Override
    public void sendExternal(Long configId, String title, String content, String recipients, String language) {
        sendExternal(configId, title, content, recipients, language, false);
    }

    @Override
    public void sendTelegramExternal(Long configId, String title, String content, String recipients, String language) {
        sendExternal(configId, title, content, recipients, language, true);
    }

    private void sendExternal(Long configId, String title, String content, String recipients, String language, boolean telegramOnly) {
        NotificationChannelConfig record = requireChannel(configId);
        if (telegramOnly && !"TELEGRAM".equals(record.getChannelType()))
            throw new BusinessException("server.notification.channel.typeInvalid");
        if (!Integer.valueOf(1).equals(record.getEnabled())) {
            throw new BusinessException("server.notification.channel.disabled");
        }
        Map<String, Object> config = readConfig(record);
        String targets = StringUtils.hasText(recipients) ? recipients.trim() : record.getDefaultRecipients();
        try {
            switch (record.getChannelType()) {
                case "TELEGRAM" -> sendTelegram(config, targets, title, content);
                case "DINGTALK" -> sendDingTalk(config, title, content);
                case "EMAIL" -> sendEmail(config, targets, title, content, language);
                default -> throw new BusinessException("server.notification.channel.typeInvalid");
            }
        } catch (BusinessException e) {
            if ("TELEGRAM".equals(record.getChannelType())) {
                telegramFailureAlertService.notifyFailure(record);
            }
            throw e;
        } catch (Exception e) {
            if ("TELEGRAM".equals(record.getChannelType())) {
                telegramFailureAlertService.notifyFailure(record);
            }
            throw new BusinessException("server.notification.external.sendFailed");
        }
    }

    private void sendTelegram(Map<String, Object> config, String recipients, String title, String content) {
        String token = required(config, "botToken");
        List<String> chatIds = splitRecipients(recipients);
        if (chatIds.isEmpty()) {
            throw new BusinessException("server.notification.recipient.required");
        }
        // 标题按纯文本转义；正文共用 Telegram 安全格式并保留实际换行。
        String text = TelegramHtmlSanitizer.sanitize(
                StringUtils.hasText(title)
                        ? org.springframework.web.util.HtmlUtils.htmlEscape(title.trim())
                                + "\n" + (content == null ? "" : content)
                        : content);
        for (String chatId : chatIds) {
            requireTelegramSuccess(telegramBotClient.sendMessage(token, chatId, text));
        }
    }

    private void sendDingTalk(Map<String, Object> config, String title, String content) {
        String webhook = required(config, "webhook");
        String secret = text(config.get("secret"));
        String message = dingTalkContent(text(config.get("keyword")), title, content);
        URI uri = StringUtils.hasText(secret) ? signedDingTalkUri(webhook, secret) : URI.create(webhook);
        Map<?, ?> response = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "msgtype", "text",
                        "text", Map.of("content", message)
                ))
                .retrieve()
                .body(Map.class);
        requireDingTalkSuccess(response);
    }

    /** 钉钉业务失败也返回 HTTP 200，必须继续检查响应体中的 errcode。 */
    static void requireDingTalkSuccess(Map<?, ?> response) {
        Object errorCode = response == null ? null : response.get("errcode");
        if (errorCode == null || !"0".equals(String.valueOf(errorCode))) {
            throw new BusinessException("server.notification.external.sendFailed");
        }
    }

    /** 钉钉按关键字校验安全规则；配置关键字时确保它实际出现在消息正文中。 */
    static String dingTalkContent(String keyword, String title, String content) {
        String message = joinTitle(title, content);
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        if (!StringUtils.hasText(normalizedKeyword) || message.contains(normalizedKeyword)) {
            return message;
        }
        return normalizedKeyword + "\n" + message;
    }

    private void sendEmail(Map<String, Object> config, String recipients, String title, String content,
                           String language) {
        // 不依赖全局 spring.mail 配置，每个渠道都可以拥有独立 SMTP / OAuth2 账号。
        List<String> targets = splitRecipients(recipients);
        if (targets.isEmpty()) {
            throw new BusinessException("server.notification.recipient.required");
        }
        org.springframework.mail.javamail.JavaMailSenderImpl sender = new org.springframework.mail.javamail.JavaMailSenderImpl();
        sender.setHost(required(config, "host"));
        sender.setPort(integer(config.get("port"), 587));
        sender.setUsername(required(config, "username"));
        String authMode = text(config.get("authMode"));
        sender.setPassword("GOOGLE_OAUTH".equalsIgnoreCase(authMode)
                ? resolveGoogleAccessToken(config) : required(config, "password"));
        java.util.Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", String.valueOf(booleanValue(config.get("startTls"), true)));
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "15000");
        properties.put("mail.smtp.writetimeout", "15000");
        if ("GOOGLE_OAUTH".equalsIgnoreCase(authMode)) {
            properties.put("mail.smtp.auth.mechanisms", "XOAUTH2");
            properties.put("mail.smtp.auth.login.disable", "true");
            properties.put("mail.smtp.auth.plain.disable", "true");
        }
        try {
            jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper =
                    new org.springframework.mail.javamail.MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(StringUtils.hasText(text(config.get("from")))
                    ? text(config.get("from")) : sender.getUsername());
            helper.setTo(targets.toArray(String[]::new));
            helper.setSubject(StringUtils.hasText(title)
                    ? title : translated("server.notification.email.defaultSubject", language));
            helper.setText(content == null ? "" : content, booleanValue(config.get("html"), true));
            sender.send(message);
        } catch (jakarta.mail.MessagingException e) {
            throw new BusinessException("server.notification.email.buildFailed");
        }
    }

    private NotificationChannelConfig requireChannel(Long id) {
        NotificationChannelConfig record = id == null ? null : channelRepository.getById(id);
        if (record == null) {
            throw new BusinessException("server.notification.channel.notFound");
        }
        return record;
    }

    private NotificationChannelConfig requireTelegramChannel(Long id, boolean requireEnabled) {
        NotificationChannelConfig record = requireChannel(id);
        if (!"TELEGRAM".equals(record.getChannelType())) {
            throw new BusinessException("server.notification.channel.typeInvalid");
        }
        if (requireEnabled && !Integer.valueOf(1).equals(record.getEnabled())) {
            throw new BusinessException("server.notification.channel.disabled");
        }
        return record;
    }

    private NotificationChannelConfig requireEmailOAuthChannel(Long id) {
        NotificationChannelConfig record = requireChannel(id);
        Map<String, Object> config = readConfig(record);
        if (!"EMAIL".equals(record.getChannelType())
                || !"GOOGLE_OAUTH".equalsIgnoreCase(text(config.get("authMode")))) {
            throw new BusinessException("server.notification.google.channelInvalid");
        }
        return record;
    }

    private NotificationChannelView toView(NotificationChannelConfig record) {
        Map<String, Object> masked = new LinkedHashMap<>(readConfig(record));
        masked.replaceAll((key, value) -> isSensitiveKey(key) && StringUtils.hasText(text(value))
                ? mask(text(value)) : value);
        if ("TELEGRAM".equals(record.getChannelType())) {
            masked.put("pollingStatus", Integer.valueOf(1).equals(record.getEnabled())
                    ? telegramBotClient.pollingStatus(text(readConfig(record).get("botToken")))
                    : Map.of("status", "DISABLED"));
        }
        return new NotificationChannelView(record.getId(), record.getName(), record.getChannelType(),
                record.getEnabled(), masked, record.getDefaultRecipients(), record.getCreatedAt(), record.getUpdatedAt());
    }

    private Map<String, Object> readConfig(NotificationChannelConfig record) {
        try {
            String json = cipher.decrypt(record.getEncryptedConfig());
            return StringUtils.hasText(json)
                    ? objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { })
                    : new LinkedHashMap<>();
        } catch (Exception e) {
            throw new IllegalStateException("通知渠道配置读取失败，配置 ID：" + record.getId(), e);
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("通知渠道配置不是有效 JSON", e);
        }
    }

    private void validateConfig(String type, Map<String, Object> config) {
        switch (type) {
            case "EMAIL" -> {
                required(config, "host");
                required(config, "username");
                String authMode = text(config.get("authMode"));
                if ("GOOGLE_OAUTH".equalsIgnoreCase(authMode)) {
                    // 首次保存授权客户端后才能跳转 Google；token 在回调完成时写入。
                    required(config, "clientId");
                    required(config, "clientSecret");
                    required(config, "redirectUri");
                } else {
                    required(config, "password");
                }
            }
            case "TELEGRAM" -> {
                try { TelegramBotClient.apiUri(required(config, "botToken"), "getMe", null); }
                catch (IllegalArgumentException exception) { throw new TelegramApiException("INVALID_TOKEN", 1800); }
            }
            case "DINGTALK" -> required(config, "webhook");
            default -> throw new BusinessException("server.notification.channel.typeInvalid");
        }
    }

    private String normalizeChannelType(String type) {
        String value = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!CHANNEL_TYPES.contains(value)) {
            throw new BusinessException("server.notification.channel.typeInvalid");
        }
        return value;
    }

    private String normalizeTemplateChannelType(String type) {
        String value = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!TEMPLATE_CHANNEL_TYPES.contains(value)) {
            throw new BusinessException("server.notification.channel.typeInvalid");
        }
        return value;
    }

    /**
     * 沿用项目已有翻译模板：2 邮件、3 站内信、6 Telegram/钉钉通知。
     */
    private int templateTranslationType(String channelType) {
        return switch (normalizeTemplateChannelType(channelType)) {
            case "EMAIL" -> EMAIL_TEMPLATE_TRANSLATION_TYPE;
            case "INTERNAL" -> INTERNAL_TEMPLATE_TRANSLATION_TYPE;
            case "TELEGRAM", "DINGTALK" -> MESSAGE_TEMPLATE_TRANSLATION_TYPE;
            default -> throw new BusinessException("server.notification.channel.typeInvalid");
        };
    }

    /** 邮件和站内信使用通用 HTML；Telegram 使用受限 HTML；钉钉使用纯文本。 */
    private int templateContentProp(String channelType) {
        String normalized = normalizeTemplateChannelType(channelType);
        if ("EMAIL".equals(normalized) || "INTERNAL".equals(normalized)) {
            return 1;
        }
        return "TELEGRAM".equals(normalized) ? 2 : 0;
    }

    private List<NotificationTemplateView> toTemplateViews(List<NotificationTemplate> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<NotificationTemplateLanguageView> languages = enabledTemplateLanguages();
        Set<String> enabledCodes = languages.stream().map(NotificationTemplateLanguageView::lang)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> types = records.stream().map(record -> templateTranslationType(record.getChannelType()))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> keys = records.stream()
                .flatMap(record -> java.util.stream.Stream.of(
                        templateTitleKey(record.getCode()), templateContentKey(record.getCode())))
                .collect(java.util.stream.Collectors.toSet());
        List<Translation> translationRows = translationRepository.findTemplateLocalizations(types,keys,enabledCodes);
        Map<String, Map<String, String>> values = new HashMap<>();
        if (translationRows != null) {
            for (Translation row : translationRows) {
                String lang = normalizeTemplateLanguage(row.getLang());
                if (!StringUtils.hasText(lang) || !StringUtils.hasText(row.getTvalue())) {
                    continue;
                }
                values.computeIfAbsent(templateTranslationMapKey(row.getType(), row.getTkey()), ignored ->
                                new LinkedHashMap<>())
                        .putIfAbsent(lang, row.getTvalue());
            }
        }
        return records.stream().map(record -> {
            int type = templateTranslationType(record.getChannelType());
            Map<String, String> titles = values.getOrDefault(
                    templateTranslationMapKey(type, templateTitleKey(record.getCode())), Map.of());
            Map<String, String> contents = values.getOrDefault(
                    templateTranslationMapKey(type, templateContentKey(record.getCode())), Map.of());
            List<NotificationTemplateLocalizationView> localizations = enabledCodes.stream()
                    .map(lang -> new NotificationTemplateLocalizationView(
                            lang,
                            titles.get(lang),
                            contents.get(lang)))
                    .toList();
            return new NotificationTemplateView(
                    record.getId(), record.getCode(), record.getName(), record.getChannelType(),
                    record.getTitleTemplate(), record.getContentTemplate(), record.getEnabled(), type,
                    languages, localizations, record.getCreatedAt(), record.getUpdatedAt());
        }).toList();
    }

    private List<NotificationTemplateLanguageView> enabledTemplateLanguages() {
        List<Language> languages = languageService.listLanguages(true);
        return languages == null ? List.of() : languages.stream()
                .filter(item -> item != null && StringUtils.hasText(item.getLang()))
                .map(item -> new NotificationTemplateLanguageView(
                        normalizeTemplateLanguage(item.getLang()), item.getName(), item.getNativeName()))
                .filter(item -> StringUtils.hasText(item.lang()))
                .distinct()
                .toList();
    }

    /** null 保留已有内容；显式列表替换启用语言内容，但省略英语仍须保留其有效旧译文。 */
    private List<NormalizedTemplateLocalization> normalizeTemplateLocalizations(
            List<NotificationTemplateLocalizationRequest> requested
    ) {
        if (requested == null) {
            return null;
        }
        Set<String> enabled = enabledTemplateLanguages().stream()
                .map(NotificationTemplateLanguageView::lang)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> seen = new HashSet<>();
        List<NormalizedTemplateLocalization> normalized = new ArrayList<>();
        for (NotificationTemplateLocalizationRequest item : requested) {
            if (item == null) {
                continue;
            }
            String lang = normalizeTemplateLanguage(item.lang());
            if (!StringUtils.hasText(lang) || !enabled.contains(lang)) {
                throw new BusinessException("server.notification.template.languageNotEnabled");
            }
            if (!seen.add(lang)) {
                throw new BusinessException("server.notification.template.languageDuplicate");
            }
            normalized.add(new NormalizedTemplateLocalization(
                    lang, trimToNull(item.title()), trimToNull(item.content())));
        }
        return List.copyOf(normalized);
    }

    private String normalizeTemplateLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return "";
        }
        String normalized = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return normalized.matches("^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$") ? normalized : "";
    }

    private List<NormalizedTemplateLocalization> requireEnglishLocalization(
            String code, Integer oldType, List<NormalizedTemplateLocalization> requested, boolean titleRequired, int contentProp
    ) {
        NormalizedTemplateLocalization english = requested == null ? null : requested.stream()
                .filter(item -> "en".equals(item.lang())).findFirst().orElse(null);
        if (english != null) {
            EnglishTranslationValidator.require(english.content(), contentProp);
            if (titleRequired) EnglishTranslationValidator.require(english.title(), 2);
            return requested;
        }
        String content = savedEnglish(templateContentKey(code), oldType);
        String title = savedEnglish(templateTitleKey(code), oldType);
        EnglishTranslationValidator.require(content, contentProp);
        if (titleRequired) EnglishTranslationValidator.require(title, 2);
        if (requested == null) return null;
        List<NormalizedTemplateLocalization> retained = new ArrayList<>(requested);
        retained.add(new NormalizedTemplateLocalization("en", title, content));
        return List.copyOf(retained);
    }

    private String savedEnglish(String key, Integer type) {
        if (type == null) return null;
        List<Translation> rows = translationService.getTranslationBatch(key, type);
        if (rows == null) return null;
        return rows.stream().filter(row -> "en".equals(normalizeTemplateLanguage(row.getLang())))
                .map(Translation::getTvalue).filter(StringUtils::hasText)
                .findFirst().orElse(null);
    }

    private void replaceEnabledTemplateLocalizations(
            NotificationTemplate template,
            int translationType,
            List<NormalizedTemplateLocalization> localizations
    ) {
        List<String> enabled = enabledTemplateLanguages().stream()
                .map(NotificationTemplateLanguageView::lang).toList();
        if (!enabled.isEmpty()) {
            translationRepository.deleteTemplateLocalizations(List.of(translationType),List.of(templateTitleKey(template.getCode()),templateContentKey(template.getCode())),enabled);
        }
        for (NormalizedTemplateLocalization item : localizations) {
            upsertTemplateTranslation(templateTitleKey(template.getCode()), item.lang(), item.title(),
                    translationType, 0, template.getName());
            upsertTemplateTranslation(templateContentKey(template.getCode()), item.lang(), item.content(),
                    translationType, templateContentProp(template.getChannelType()), template.getName());
        }
    }

    private void migrateTemplateTranslations(String code, int oldType, int newType) {
        List<String> keys = List.of(templateTitleKey(code), templateContentKey(code));
        List<Translation> old = translationRepository.findTemplateLocalizations(List.of(oldType),keys,null);
        if (old != null) {
            for (Translation item : old) {
                upsertTemplateTranslation(item.getTkey(), item.getLang(), item.getTvalue(), newType,
                        item.getProp() == null ? 0 : item.getProp(), item.getTitle());
            }
        }
        translationRepository.deleteTemplateLocalizations(List.of(oldType),keys,null);
    }

    private void upsertTemplateTranslation(
            String key, String language, String value, int type, int prop, String title
    ) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(language)) {
            return;
        }
        Translation existing = translationRepository.findByKeyLanguageType(key,language,type);
        Translation record = existing == null ? new Translation() : existing;
        record.setTkey(key);
        record.setLang(language);
        record.setTvalue(value);
        record.setType(type);
        record.setProp(prop);
        record.setTitle(title);
        record.setIsDel(0);
        record.setActionTime(SundryUtils.getTime());
        if (existing == null) {
            translationService.save(record);
        } else {
            translationService.updateById(record);
        }
    }

    private String templateTranslationMapKey(Integer type, String key) {
        return type + "\u0000" + key;
    }

    private String templateTitleKey(String code) {
        return templateTranslationKey(code, "title");
    }

    private String templateContentKey(String code) {
        return templateTranslationKey(code, "content");
    }

    private String templateTranslationKey(String code, String field) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        String key = TEMPLATE_TRANSLATION_KEY_PREFIX + normalized + "." + field;
        if (key.length() > 100) {
            throw new BusinessException("server.notification.template.codeTooLong");
        }
        return key;
    }

    private record NormalizedTemplateLocalization(String lang, String title, String content) {
    }

    private String generateTemplateCode() {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder code = new StringBuilder("N");
        for (int index = 0; index < 10; index++) code.append(alphabet.charAt(TEMPLATE_CODE_RANDOM.nextInt(alphabet.length())));
        return code.toString();
    }

    private String required(Map<String, Object> config, String key) {
        String value = text(config.get(key));
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("server.notification.channel.configIncomplete");
        }
        return value;
    }

    static boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.replace("_", "").toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(normalized);
    }

    private boolean isMasked(Object value) {
        String text = text(value);
        return text != null && text.startsWith("******");
    }

    private String mask(String value) {
        int visible = Math.min(4, Math.max(0, value.length() / 4));
        return "******" + (visible == 0 ? "" : value.substring(value.length() - visible));
    }

    static List<String> splitRecipients(String recipients) {
        if (!StringUtils.hasText(recipients)) {
            return List.of();
        }
        return java.util.Arrays.stream(recipients.split("[\\s,;，；]+"))
                .map(String::trim).filter(StringUtils::hasText).distinct().toList();
    }

    private static String joinTitle(String title, String content) {
        return StringUtils.hasText(title) ? title.trim() + "\n" + (content == null ? "" : content) : content;
    }

    private String translated(String key, String language) {
        return translationService.getTranslate(key, language);
    }

    private String renderTranslated(String key, String language, Map<String, ?> variables) {
        String result = translated(key, language);
        for (Map.Entry<String, ?> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return result;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String randomState() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private int integer(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String resolveGoogleAccessToken(Map<String, Object> config) {
        if (StringUtils.hasText(text(config.get("refreshToken")))) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", required(config, "clientId"));
            form.add("client_secret", required(config, "clientSecret"));
            form.add("refresh_token", required(config, "refreshToken"));
            form.add("grant_type", "refresh_token");
            Map<?, ?> response = restClient.post()
                    .uri("https://oauth2.googleapis.com/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            String accessToken = response == null ? null : text(response.get("access_token"));
            if (!StringUtils.hasText(accessToken)) {
                throw new BusinessException("server.notification.google.oauthFailed");
            }
            return accessToken;
        }
        return required(config, "accessToken");
    }

    private URI signedDingTalkUri(String webhook, String secret) {
        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = Base64.getEncoder().encodeToString(
                    mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
            String separator = webhook.contains("?") ? "&" : "?";
            return URI.create(webhook + separator + "timestamp=" + timestamp + "&sign="
                    + URLEncoder.encode(sign, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("钉钉签名生成失败", e);
        }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }
}
