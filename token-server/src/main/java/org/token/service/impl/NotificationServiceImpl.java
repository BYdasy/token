package org.token.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.token.repository.system.AccountRepository;
import org.token.repository.system.SystemNotificationRepository;
import org.token.repository.system.RoleRepository;
import org.token.model.entity.Account;
import org.token.model.entity.SystemNotification;
import org.token.model.entity.Role;
import org.token.model.request.FilterParams;
import org.token.model.request.notification.NotificationSendRequest;
import org.token.model.request.QueryParams;
import org.token.model.response.TranslationCompletionResult;
import org.token.model.response.NotificationTargetsView;
import org.token.exception.BusinessException;
import org.token.service.NotificationPushService;
import org.token.service.NotificationService;
import org.token.service.TranslationService;
import org.token.tools.media.SundryUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashSet;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Set<String> TARGET_TYPES = Set.of("ALL", "USER", "ROLE");

    private final SystemNotificationRepository notificationRepository;
    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final TranslationService translationService;
    private final NotificationPushService notificationPushService;
    private final NotificationTemplateRenderer templateRenderer;

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationServiceImpl(SystemNotificationRepository notificationRepository,AccountRepository accountRepository,
            RoleRepository roleRepository,TranslationService translationService,NotificationPushService notificationPushService,
            NotificationTemplateRenderer templateRenderer) {
        this.notificationRepository=notificationRepository;this.accountRepository=accountRepository;this.roleRepository=roleRepository;
        this.translationService=translationService;this.notificationPushService=notificationPushService;this.templateRenderer=templateRenderer;
    }
    public NotificationServiceImpl(SystemNotificationRepository notificationRepository,AccountRepository accountRepository,
            RoleRepository roleRepository,TranslationService translationService,NotificationPushService notificationPushService) {
        this(notificationRepository,accountRepository,roleRepository,translationService,notificationPushService,null);
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<SystemNotification> myNotifications(Integer uid, QueryParams params, boolean includeHidden) {
        requireUid(uid);
        return withAccountNames(findPage(params,uid,includeHidden));
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<SystemNotification> manageNotifications(QueryParams params) {
        return withAccountNames(findPage(params,null,true));
    }

    /** 只查询当前页已经授权的通知关联账号，不返回账号资料或安全字段。 */
    private IPage<SystemNotification> withAccountNames(IPage<SystemNotification> page) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (SystemNotification record : page.getRecords()) {
            if (record.getRecipientUid() != null) ids.add(record.getRecipientUid());
            if (record.getSenderUid() != null) ids.add(record.getSenderUid());
        }
        Map<Integer, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Account account : accountRepository.namesByIds(ids)) {
                names.put(account.getUid(), account.getUsername());
            }
        }
        for (SystemNotification record : page.getRecords()) {
            record.setRecipientUsername(names.get(record.getRecipientUid()));
            record.setSenderUsername(names.get(record.getSenderUid()));
        }
        return page;
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(Integer uid) {
        requireUid(uid);
        return notificationRepository.unreadCount(uid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Integer uid, Long id) {
        requireUid(uid);
        if (id == null || id <= 0) {
            throw new BusinessException("server.notification.id.required");
        }
        notificationRepository.markRead(uid,id,SundryUtils.getTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAllRead(Integer uid) {
        requireUid(uid);
        notificationRepository.markAllRead(uid,SundryUtils.getTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void hideForUser(Integer uid, Long id) {
        requireUid(uid);
        if (id == null || id <= 0) {
            throw new BusinessException("server.notification.id.required");
        }
        notificationRepository.hide(uid,id,SundryUtils.getTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearForUser(Integer uid) {
        requireUid(uid);
        LocalDateTime now = SundryUtils.getTime();
        notificationRepository.clear(uid,now);
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationTargetsView targets() {
        List<Account> accounts = accountRepository.enabledTargets();
        List<Role> roles = roleRepository.targets();
        return new NotificationTargetsView(
                accounts == null ? List.of() : accounts.stream()
                        .filter(account -> account.getUid() != null)
                        .map(account -> new NotificationTargetsView.AccountTarget(
                                account.getUid(), account.getUsername()))
                        .toList(),
                roles == null ? List.of() : roles.stream()
                        .filter(role -> role.getId() != null)
                        .map(role -> new NotificationTargetsView.RoleTarget(role.getId(), role.getName()))
                        .toList()
        );
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public int send(NotificationSendRequest request, Integer senderUid) {
        if (request == null) {
            throw new BusinessException("server.notification.required");
        }
        String targetType = normalize(request.target().type(), TARGET_TYPES,
                "server.notification.targetType.invalid");
        String title = request.title();
        String content = request.content();
        String severity=severity(request.severity());
        if(request.variables()!=null&&(request.variables().size()>50||request.variables().entrySet().stream().anyMatch(entry->entry.getKey()==null||entry.getKey().length()>60||entry.getValue()!=null&&entry.getValue().length()>4000)))
            throw new BusinessException("server.notification.field.tooLong");
        if (!StringUtils.hasText(title) && !StringUtils.hasText(content)&&!StringUtils.hasText(request.templateCode())) {
            throw new BusinessException("server.notification.content.required");
        }

        List<Integer> recipientUids = resolveRecipients(targetType, request.target().ids());
        if (recipientUids.isEmpty()) {
            throw new BusinessException("server.notification.recipient.notFound");
        }
        for (Integer recipientUid : recipientUids) {
            String actualTitle=title,actualContent=content;
            if(StringUtils.hasText(request.templateCode())) {
                var rendered=templateRenderer==null?null:templateRenderer.render(request.templateCode(),accountLanguage(recipientUid),request.variables());
                if(rendered==null||!"INTERNAL".equals(rendered.channelType()))throw new BusinessException("server.notification.template.notFound");
                actualTitle=rendered.title();actualContent=rendered.content();
            }
            sendInternalMessage(recipientUid, actualTitle, actualContent, senderUid,severity);
            if (request.popupEnabled()) {
                sendPopup(recipientUid, actualTitle, actualContent,severity);
            }
        }
        return recipientUids.size();
    }

    @Override
    public void sendInternalMessage(Integer recipientUid, String title, String htmlContent, Integer senderUid) {
        sendInternalMessage(recipientUid,title,htmlContent,senderUid,"INFO");
    }

    @Override
    public void sendInternalMessage(Integer recipientUid,String title,String htmlContent,Integer senderUid,String severity) {
        requireUid(recipientUid);
        SystemNotification record = new SystemNotification();
        record.setRecipientUid(recipientUid);
        record.setSenderUid(senderUid);
        record.setTitle(trimToNull(title));
        record.setContent(htmlContent == null ? "" : htmlContent);
        record.setSeverity(severity(severity));
        record.setReadStatus(0);
        record.setHiddenStatus(0);
        record.setCreatedAt(SundryUtils.getTime());
        if (!notificationRepository.save(record)) {
            throw new BusinessException("server.notification.sendFailed");
        }
        afterCommit(()->notificationPushService.pushMessage(record));
    }

    @Override
    public void sendPopup(Integer recipientUid, String title, String htmlContent) {
        requireUid(recipientUid);
        afterCommit(()->notificationPushService.pushPopup(recipientUid,trimToNull(title),htmlContent));
    }

    @Override
    public void sendPopup(Integer recipientUid,String title,String htmlContent,String severity) {
        requireUid(recipientUid);String style=severity(severity);
        afterCommit(()->notificationPushService.pushPopup(recipientUid,trimToNull(title),htmlContent,style));
    }

    @Override
    public void notifySuperAdmins(String title, String htmlContent) {
        List<Role> superRoles = roleRepository.superRoleIds();
        if (superRoles == null || superRoles.isEmpty()) {
            return;
        }
        List<Integer> roleIds = superRoles.stream().map(Role::getId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        List<Account> accounts = accountRepository.recipients("ROLE",roleIds);
        if (accounts == null) {
            return;
        }
        for (Integer uid : accounts.stream().map(Account::getUid)
                .filter(java.util.Objects::nonNull).distinct().toList()) {
            sendInternalMessage(uid, title, htmlContent, null);
        }
    }

    @Override
    public void notifyTranslationCompleted(TranslationCompletionResult result, Integer recipientUid) {
        if (result == null || recipientUid == null || recipientUid <= 0) {
            return;
        }
        if(templateRenderer!=null)popupTemplate("TR_DONE",recipientUid,accountLanguage(recipientUid),
                Map.of("templateName",result.templateName(),"inserted",result.inserted(),"missingAfter",result.missingAfter()),"SUCCESS");
    }

    @Override
    public void notifyTranslationFailed(String templateName, String errorKey, Integer recipientUid) {
        if (recipientUid == null || recipientUid <= 0) {
            return;
        }
        String language = accountLanguage(recipientUid);
        String reason = translationService.getTranslate(
                StringUtils.hasText(errorKey) ? errorKey : "server.translation.task.failed",
                language
        );
        if(templateRenderer!=null)popupTemplate("TR_FAIL",recipientUid,language,Map.of("templateName",StringUtils.hasText(templateName)?templateName:"-","reason",reason),"ERROR");
    }

    private String accountLanguage(Integer uid) {
        Account account = uid == null ? null : accountRepository.getById(uid);
        return account == null ? null : account.getLocale();
    }

    @Override
    public void notifyTelegramFailure(String channelName,Long channelId) {
        List<Role> roles=roleRepository.superRoleIds();
        if(roles==null||roles.isEmpty()||templateRenderer==null)return;
        List<Integer> ids=roles.stream().map(Role::getId).toList();
        List<Account> recipients=accountRepository.recipients("ROLE",ids);
        if(recipients==null)return;
        for(Account recipient:recipients) {
            var rendered=templateRenderer.render("TG_FAIL",accountLanguage(recipient.getUid()),Map.of("channelName",java.util.Objects.toString(channelName,"-"),"channelId",java.util.Objects.toString(channelId,"")));
            if(rendered!=null)sendInternalMessage(recipient.getUid(),rendered.title(),rendered.content(),null,"WARNING");
        }
    }
    private void popupTemplate(String code,Integer uid,String language,Map<String,?> variables,String severity){
        var rendered=templateRenderer.render(code,language,variables);
        if(rendered!=null)sendPopup(uid,rendered.title(),rendered.content(),severity);
    }
    static String severity(String raw) {
        String value=raw==null||raw.isBlank()?"INFO":raw.trim().toUpperCase(Locale.ROOT);
        if(!Set.of("INFO","SUCCESS","WARNING","ERROR").contains(value))throw new BusinessException("server.notification.severity.invalid");
        return value;
    }
    private static void afterCommit(Runnable action) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){action.run();}});
        else action.run();
    }

    private List<Integer> resolveRecipients(String targetType, List<Integer> targetIds) {

        List<Integer> ids = targetIds == null ? List.of() : targetIds.stream()
                .filter(java.util.Objects::nonNull).filter(id -> id > 0).distinct().toList();
        switch (targetType) {
            case "USER" -> {
                if (ids.isEmpty()) {
                    throw new BusinessException("server.notification.recipient.required");
                }

            }
            case "ROLE" -> {
                if (ids.isEmpty()) {
                    throw new BusinessException("server.notification.recipient.required");
                }

            }
            case "ALL" -> { }
            default -> throw new BusinessException("server.notification.targetType.invalid");
        }
        List<Account> accounts = accountRepository.recipients(targetType,ids);
        if (accounts == null) {
            return List.of();
        }
        return accounts.stream().map(Account::getUid).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private IPage<SystemNotification> findPage(QueryParams params,Integer uid,boolean includeHidden) {
        FilterParams filters = params == null ? null : params.getFilterParams();
        String status = filters == null ? null : trimToNull(filters.getStatus());
        Integer readStatus = "0".equals(status) || "1".equals(status) ? Integer.valueOf(status) : null;
        String search = filters == null ? null : trimToNull(filters.getSearchInputValue());
        return notificationRepository.findPage(page(params),uid,includeHidden,readStatus,
                search,
                filters==null?null:trimToNull(filters.getStartDate()),filters==null?null:trimToNull(filters.getEndDate()));
    }

    private Page<SystemNotification> page(QueryParams params) {
        long current = params != null && params.getPage() != null && params.getPage().getCurrent() != null
                ? Math.max(1, params.getPage().getCurrent()) : 1;
        long size = params != null && params.getPage() != null && params.getPage().getSize() != null
                ? Math.min(200, Math.max(1, params.getPage().getSize())) : 20;
        return new Page<>(current, size);
    }

    private String normalize(String value, Collection<String> supported, String errorKey) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!supported.contains(normalized)) {
            throw new BusinessException(errorKey);
        }
        return normalized;
    }

    private void requireUid(Integer uid) {
        if (uid == null || uid <= 0) {
            throw new BusinessException("server.user.uidRequired");
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
