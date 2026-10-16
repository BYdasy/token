package org.token.service.impl;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.model.entity.NotificationChannelConfig;
import org.token.model.entity.NotificationTelegramBinding;
import org.token.model.request.notification.TelegramBindingSaveRequest;
import org.token.model.request.notification.TelegramTemplateSendRequest;
import org.token.model.response.TelegramSendOptionsView;
import org.token.repository.system.NotificationChannelConfigRepository;
import org.token.repository.system.NotificationTelegramBindingRepository;
import org.token.repository.system.NotificationTemplateRepository;
import org.token.service.NotificationConfigService;
import org.token.service.compute.ComputeAgentScope;
import org.token.tools.media.SundryUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.stream.Collectors;

/** Telegram 人工绑定与手动发送；数据库校验结束后才外呼，不在资金或配置事务中等待网络。 */
@Service
@RequiredArgsConstructor
public class TelegramNotificationService {
    private final NotificationTelegramBindingRepository bindings;
    private final NotificationChannelConfigRepository channels;
    private final NotificationTemplateRepository templates;
    private final NotificationTemplateRenderer renderer;
    private final NotificationConfigService config;
    private final ComputeAgentScope scope;

    public List<NotificationTelegramBinding> list(Long channelId) {
        if (channelId != null) requireChannel(channelId, false);
        return bindings.findBindings(channelId, false);
    }

    public TelegramSendOptionsView sendOptions() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean sender = authentication != null && authentication.isAuthenticated() && authentication.getAuthorities().stream()
                .anyMatch(authority -> Set.of("60702","super").contains(authority.getAuthority()));
        // 基础设置仅向全局管理员提供安全选项；原通知发送权限保持原有授权语义。
        if (!sender) scope.requireGlobalScope();
        var channelOptions = channels.telegram(true).stream()
                .map(channel -> new TelegramSendOptionsView.Channel(channel.getId(), channel.getName())).toList();
        var templateOptions = templates.findAllOrdered().stream()
                .filter(template -> "TELEGRAM".equals(template.getChannelType()) && Integer.valueOf(1).equals(template.getEnabled()))
                .map(template -> new TelegramSendOptionsView.Template(template.getCode(), "T" + template.getId(), template.getName(), renderer.variables(template))).toList();
        return new TelegramSendOptionsView(channelOptions, bindings.findBindings(null, true), templateOptions);
    }

    @Transactional(rollbackFor = Exception.class)
    public NotificationTelegramBinding save(TelegramBindingSaveRequest request) {
        if (request == null || request.channelId() == null || request.channelId() <= 0
                || request.id() != null && request.id() <= 0 || request.chatId() == null
                || !request.chatId().matches("-?[1-9][0-9]{0,19}") || !StringUtils.hasText(request.name())
                || request.name().trim().length() > 100 || !Set.of("PRIVATE", "GROUP", "CHANNEL").contains(Objects.toString(request.chatType(), ""))
                || request.enabled() != null && request.enabled() != 0 && request.enabled() != 1)
            throw new BusinessException("server.notification.telegram.bindingInvalid");
        NotificationChannelConfig channel = bindings.lockChannel(request.channelId());
        checkChannel(channel, false);
        NotificationTelegramBinding previous = request.id() == null ? null : bindings.lockBinding(request.id());
        if (request.id() != null && (previous == null || !request.channelId().equals(previous.getChannelId())))
            throw new BusinessException("server.notification.telegram.bindingInvalid");
        NotificationTelegramBinding duplicate = bindings.findByChat(request.channelId(), request.chatId());
        if (duplicate != null && !Objects.equals(duplicate.getId(), request.id()))
            throw new BusinessException("server.notification.telegram.bindingExists");
        NotificationTelegramBinding record = new NotificationTelegramBinding();
        record.setId(request.id()); record.setChannelId(request.channelId()); record.setChatId(request.chatId());
        record.setName(request.name().trim()); record.setChatType(request.chatType());
        record.setEnabled(request.enabled() == null ? 1 : request.enabled());
        record.setUpdatedAt(SundryUtils.getTime());
        record.setCreatedAt(previous == null ? record.getUpdatedAt() : previous.getCreatedAt());
        try {
            if (!(previous == null ? bindings.save(record) : bindings.updateById(record)))
                throw new BusinessException("server.notification.telegram.bindingInvalid");
        } catch (DuplicateKeyException duplicateKey) {
            throw new BusinessException("server.notification.telegram.bindingExists");
        }
        return record;
    }

    /** 完整选择一次校验；任何越渠道、停用或缺失编号都拒绝，不能静默漏选或回退默认接收人。 */
    public List<NotificationTelegramBinding> validateTargets(Long channelId, String targetMode, List<Long> bindingIds) {
        requireChannel(channelId, true);
        List<NotificationTelegramBinding> enabled = bindings.findBindings(channelId, true);
        if ("ALL".equals(targetMode)) {
            if (bindingIds != null && !bindingIds.isEmpty()) throw new BusinessException("server.notification.telegram.targetsInvalid");
            if (enabled.isEmpty()) throw new BusinessException("server.notification.recipient.required");
            return enabled;
        }
        if (!"SELECTED".equals(targetMode) || bindingIds == null || bindingIds.isEmpty()
                || bindingIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new BusinessException("server.notification.telegram.targetsInvalid");
        Set<Long> selected = new LinkedHashSet<>(bindingIds);
        Map<Long, NotificationTelegramBinding> available = enabled.stream()
                .collect(Collectors.toMap(NotificationTelegramBinding::getId, value -> value));
        if (!available.keySet().containsAll(selected)) throw new BusinessException("server.notification.telegram.targetsInvalid");
        return selected.stream().map(available::get).toList();
    }

    public int sendBound(Long channelId, String targetMode, List<Long> bindingIds, String title, String content, String language) {
        List<NotificationTelegramBinding> targets = validateTargets(channelId, targetMode, bindingIds);
        if (!StringUtils.hasText(content)) throw new BusinessException("server.notification.content.required");
        String sanitized = TelegramHtmlSanitizer.sanitize(content);
        if (!StringUtils.hasText(sanitized)) throw new BusinessException("server.notification.content.required");
        // 下游发送器会再次规范化 HTML；把已规范化换行还原为 br，避免第二次解析折叠段落。
        config.sendTelegramExternal(channelId, title, sanitized.replace("\n", "<br>"),
                targets.stream().map(NotificationTelegramBinding::getChatId).distinct().collect(Collectors.joining(",")), language);
        return targets.size();
    }

    public int send(TelegramTemplateSendRequest request, String fallbackLanguage) {
        if (request == null) throw new BusinessException("server.notification.content.required");
        String language = StringUtils.hasText(request.language()) ? request.language().trim() : fallbackLanguage;
        String title = request.title(), content = request.content();
        if (StringUtils.hasText(request.templateCode())) {
            var rendered = renderer.render(request.templateCode().trim(), language, request.variables());
            if (rendered == null) throw new BusinessException("server.notification.template.notFound");
            if (!"TELEGRAM".equals(rendered.channelType())) throw new BusinessException("server.notification.channel.typeInvalid");
            title = rendered.title(); content = rendered.content();
        }
        return sendBound(request.channelId(), request.targetMode(), request.bindingIds(), title, content, language);
    }

    private NotificationChannelConfig requireChannel(Long id, boolean enabled) {
        NotificationChannelConfig channel = id == null ? null : channels.getById(id);
        checkChannel(channel, enabled);
        return channel;
    }
    private void checkChannel(NotificationChannelConfig channel, boolean enabled) {
        if (channel == null) throw new BusinessException("server.notification.channel.notFound");
        if (!"TELEGRAM".equals(channel.getChannelType())) throw new BusinessException("server.notification.channel.typeInvalid");
        if (enabled && !Integer.valueOf(1).equals(channel.getEnabled())) throw new BusinessException("server.notification.channel.disabled");
    }
}
