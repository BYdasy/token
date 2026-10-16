package org.token.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.token.repository.dao.AccountMapper;
import org.token.repository.dao.SystemNotificationMapper;
import org.token.repository.dao.RoleMapper;
import org.token.model.entity.Account;
import org.token.model.entity.SystemNotification;
import org.token.model.entity.Role;
import org.token.model.request.notification.NotificationSendRequest;
import org.token.model.request.notification.NotificationTargetRequest;
import org.token.service.NotificationPushService;
import org.token.service.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private SystemNotificationMapper notificationMapper;
    @Mock
    private AccountMapper accountMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private TranslationService translationService;
    @Mock
    private NotificationPushService notificationPushService;
    @Mock private NotificationTemplateRenderer renderer;

    private NotificationServiceImpl service;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementSearchCombinesDatesStatusAndTextWithoutChangingPaging() {
        org.token.model.request.QueryParams request = new org.token.model.request.QueryParams();
        org.token.model.request.FilterParams filter = new org.token.model.request.FilterParams();
        filter.setStartDate("2026-09-01T00:00:00");
        filter.setEndDate("2026-09-02T00:00:00");
        filter.setStatus("0");
        filter.setSearchInputValue("到账");
        request.setFilterParams(filter);
        org.token.model.request.QueryParams.Page page = new org.token.model.request.QueryParams.Page();
        page.setCurrent(3);
        page.setSize(50);
        request.setPage(page);
        when(notificationMapper.findPage(any(),any(),anyBoolean(),any(),any(),any(),any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.manageNotifications(request);

        ArgumentCaptor<com.baomidou.mybatisplus.extension.plugins.pagination.Page> pages = ArgumentCaptor.forClass(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class);
        verify(notificationMapper).findPage(pages.capture(),isNull(),eq(true),eq(0),eq("到账"),
                eq("2026-09-01T00:00:00"),eq("2026-09-02T00:00:00"));
        assertEquals(3,pages.getValue().getCurrent());
        assertEquals(50,pages.getValue().getSize());
    }

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace("org.exp.test.SystemNotificationMapper");
        TableInfoHelper.initTableInfo(assistant, SystemNotification.class);
        TableInfoHelper.initTableInfo(assistant, Account.class);
        TableInfoHelper.initTableInfo(assistant, Role.class);
        service = new NotificationServiceImpl(new org.token.repository.system.SystemNotificationRepository(notificationMapper), new org.token.repository.system.AccountRepository(accountMapper), new org.token.repository.system.RoleRepository(roleMapper), translationService, notificationPushService,renderer);
    }

    @Test
    void notificationPageResolvesAccountNamesOnceWithoutLoadingPrivateFields() throws Exception {
        SystemNotification first = new SystemNotification();
        first.setRecipientUid(11);
        first.setSenderUid(1);
        SystemNotification second = new SystemNotification();
        second.setRecipientUid(11);
        second.setSenderUid(999);
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<SystemNotification>();
        page.setRecords(List.of(first, second));
        when(notificationMapper.findPage(any(),any(),anyBoolean(),any(),any(),any(),any())).thenReturn(page);
        Account recipient = new Account();
        recipient.setUid(11);
        recipient.setUsername("operator");
        Account sender = new Account();
        sender.setUid(1);
        sender.setUsername("admin");
        when(accountMapper.namesByIds(any())).thenReturn(List.of(recipient, sender));

        service.manageNotifications(new org.token.model.request.QueryParams());

        assertEquals("operator", first.getRecipientUsername());
        assertEquals("admin", first.getSenderUsername());
        assertEquals("operator", second.getRecipientUsername());
        org.junit.jupiter.api.Assertions.assertNull(second.getSenderUsername());
        verify(accountMapper,times(1)).namesByIds(eq(java.util.Set.of(11,1,999)));
        String sql=String.join(" ",AccountMapper.class.getMethod("namesByIds",java.util.Collection.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertTrue(sql.startsWith("<script>SELECT uid,username FROM"));
    }

    @Test
    void sendsOneRecordForEachEnabledRecipient() {
        Account first = new Account();
        first.setUid(11);
        Account second = new Account();
        second.setUid(12);
        when(accountMapper.recipients(any(),any())).thenReturn(List.of(first, second));
        when(notificationMapper.insert(any(SystemNotification.class))).thenReturn(1);

        int count = service.send(new NotificationSendRequest(
                new NotificationTargetRequest("ALL", List.of()), "标题", "<p>内容</p>", false
        ), 1);

        assertEquals(2, count);
        ArgumentCaptor<SystemNotification> captor = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notificationMapper, times(2)).insert(captor.capture());
        assertEquals(List.of(11, 12), captor.getAllValues().stream()
                .map(SystemNotification::getRecipientUid).toList());
        assertEquals(0, captor.getValue().getReadStatus());
        assertEquals(0, captor.getValue().getHiddenStatus());
        verify(notificationPushService, times(2)).pushMessage(any(SystemNotification.class));
        verify(notificationPushService, org.mockito.Mockito.never())
                .pushPopup(any(Integer.class), any(), any());
    }

    @Test
    void popupOptionKeepsMessageRecordAndAddsRealtimePopup() {
        Account account = new Account();
        account.setUid(11);
        when(accountMapper.recipients(any(),any())).thenReturn(List.of(account));
        when(notificationMapper.insert(any(SystemNotification.class))).thenReturn(1);

        int count = service.send(new NotificationSendRequest(
                new NotificationTargetRequest("ALL", List.of()), "标题", "<p>内容</p>", true
        ), 1);

        assertEquals(1, count);
        verify(notificationMapper).insert(any(SystemNotification.class));
        verify(notificationPushService).pushMessage(any(SystemNotification.class));
        verify(notificationPushService).pushPopup(11, "标题", "<p>内容</p>","INFO");
    }

    @Test
    void translationCompletionUsesPopupForCurrentUser() {
        Account account = new Account();
        account.setUid(7);
        account.setLocale("zh");
        when(accountMapper.selectById(7)).thenReturn(account);
        when(renderer.render(eq("TR_DONE"),eq("zh"),anyMap())).thenReturn(new NotificationTemplateRenderer.Rendered("翻译完成","翻译模板“后台语言”补齐完成，新增 3 条，剩余缺失 0 条。","INTERNAL"));

        service.notifyTranslationCompleted(new org.token.model.response.TranslationCompletionResult(
                5, "后台语言", 2, 10, 20, 20, 0, 10, 0, 3, 2, 3, 0
        ), 7);

        verify(notificationPushService).pushPopup(7, "翻译完成",
                "翻译模板“后台语言”补齐完成，新增 3 条，剩余缺失 0 条。","SUCCESS");
        verify(renderer).render("TR_DONE","zh",java.util.Map.of("templateName","后台语言","inserted",3,"missingAfter",0L));
        verify(notificationMapper, org.mockito.Mockito.never()).insert(any(SystemNotification.class));
    }

    @Test
    void translationFailureUsesLocalizedPopupForCurrentUser() {
        Account account = new Account();
        account.setUid(7);
        account.setLocale("zh");
        when(accountMapper.selectById(7)).thenReturn(account);
        when(translationService.getTranslate("server.translation.task.interrupted", "zh"))
                .thenReturn("服务重启或任务被中断，请重新提交");
        when(renderer.render(eq("TR_FAIL"),eq("zh"),anyMap())).thenReturn(new NotificationTemplateRenderer.Rendered("翻译补齐失败","翻译模板“后台语言”补齐失败：服务重启或任务被中断，请重新提交","INTERNAL"));

        service.notifyTranslationFailed("后台语言", "server.translation.task.interrupted", 7);

        verify(notificationPushService).pushPopup(7, "翻译补齐失败",
                "翻译模板“后台语言”补齐失败：服务重启或任务被中断，请重新提交","ERROR");
        verify(renderer).render("TR_FAIL","zh",java.util.Map.of("templateName","后台语言","reason","服务重启或任务被中断，请重新提交"));
        verify(notificationMapper, org.mockito.Mockito.never()).insert(any(SystemNotification.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clearHidesAndReadsOnlyCurrentUsersMessages() {
        service.clearForUser(7);

        verify(notificationMapper).clear(eq(7),any());
    }

    @Test
    void targetsExposeOnlyMinimalEnabledAccountAndRoleFields() {
        Account account = new Account();
        account.setUid(11);
        account.setUsername("admin");
        account.setPassword("不得返回");
        Role role = new Role();
        role.setId(2);
        role.setName("运营");
        role.setRole("不得返回");
        when(accountMapper.enabledTargets()).thenReturn(List.of(account));
        when(roleMapper.targets()).thenReturn(List.of(role));

        org.token.model.response.NotificationTargetsView result = service.targets();

        assertEquals(11, result.accounts().get(0).uid());
        assertEquals("admin", result.accounts().get(0).username());
        assertEquals(2, result.roles().get(0).id());
        assertEquals("运营", result.roles().get(0).name());
    }
}
