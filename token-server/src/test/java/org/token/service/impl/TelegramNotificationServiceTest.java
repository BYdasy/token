package org.token.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.model.entity.NotificationChannelConfig;
import org.token.model.entity.NotificationTelegramBinding;
import org.token.model.entity.NotificationTemplate;
import org.token.model.request.notification.TelegramBindingSaveRequest;
import org.token.model.request.notification.TelegramTemplateSendRequest;
import org.token.model.request.notification.TelegramTemplatePreviewRequest;
import org.token.model.request.notification.TelegramTemplateTestRequest;
import org.token.repository.system.*;
import org.token.service.NotificationConfigService;
import org.token.service.compute.ComputeAgentScope;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramNotificationServiceTest {
    final NotificationTelegramBindingRepository bindings = mock(NotificationTelegramBindingRepository.class);
    final NotificationChannelConfigRepository channels = mock(NotificationChannelConfigRepository.class);
    final NotificationTemplateRepository templates = mock(NotificationTemplateRepository.class);
    final NotificationTemplateRenderer renderer = mock(NotificationTemplateRenderer.class);
    final NotificationConfigService config = mock(NotificationConfigService.class);
    final ComputeAgentScope scope = mock(ComputeAgentScope.class);
    final TelegramNotificationService service = new TelegramNotificationService(bindings, channels, templates, renderer, config, scope);

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @BeforeEach void setup() {
        when(channels.getById(1L)).thenReturn(channel(1L, "TELEGRAM", 1));
        when(bindings.findBindings(1L, true)).thenReturn(List.of(binding(7L, "-10012345678901234567"), binding(8L, "9223372036854775807")));
    }

    @Test void selectedUsesOnlyValidatedBindingsAndKeepsLargeNegativeIdsAsText() {
        assertEquals(2, service.sendBound(1L, "SELECTED", List.of(8L, 7L, 8L), "Title", "<p>Body</p>", "en"));
        verify(config).sendTelegramExternal(1L, "Title", "Body", "9223372036854775807,-10012345678901234567", "en");
        verify(config, never()).sendExternal(any(), any(), any(), any(), any());
    }

    @Test void anyUnknownDisabledOrCrossChannelSelectionRejectsEntireSend() {
        for (List<Long> ids : List.of(List.of(7L, 99L), List.of(99L), List.of(0L), List.<Long>of()))
            assertThrows(BusinessException.class, () -> service.sendBound(1L, "SELECTED", ids, null, "Body", "en"));
        assertThrows(BusinessException.class, () -> service.sendBound(1L, "ALL", List.of(7L), null, "Body", "en"));
        assertThrows(BusinessException.class, () -> service.sendBound(1L, "UNKNOWN", List.of(7L), null, "Body", "en"));
        verifyNoInteractions(config);
    }

    @Test void allSendsOnlyCurrentEnabledChannelBindingsWithoutDefaultRecipientFallback() {
        assertEquals(2, service.sendBound(1L, "ALL", List.of(), null, "Body", "en"));
        verify(config).sendTelegramExternal(1L, null, "Body", "-10012345678901234567,9223372036854775807", "en");
        when(bindings.findBindings(1L, true)).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.sendBound(1L, "ALL", null, null, "Body", "en"));
        verifyNoMoreInteractions(config);
    }

    @Test void disabledAndNonTelegramChannelsNeverSend() {
        for (NotificationChannelConfig channel : List.of(channel(1L, "TELEGRAM", 0), channel(1L, "EMAIL", 1))) {
            when(channels.getById(1L)).thenReturn(channel);
            assertThrows(BusinessException.class, () -> service.sendBound(1L, "ALL", List.of(), null, "Body", "en"));
        }
        verifyNoInteractions(config);
    }

    @Test void directContentUsesTelegramSanitizerAndEmptyContentIsRejected() {
        service.send(new TelegramTemplateSendRequest(1L, "SELECTED", List.of(7L), null, "Title", "<p>Text<img src='x'><a href='javascript:alert(1)'>link</a></p>", null, null), "en");
        verify(config).sendTelegramExternal(1L, "Title", "Textlink", "-10012345678901234567", "en");
        assertThrows(BusinessException.class, () -> service.sendBound(1L, "ALL", List.of(), null, "<img src='x'>", "en"));
        service.sendBound(1L, "SELECTED", List.of(7L), null, "<p>First</p><p>Second</p>", "en");
        verify(config).sendTelegramExternal(1L, null, "First<br>Second", "-10012345678901234567", "en");
    }

    @Test void templateBranchRequiresEnabledTelegramTemplateAndUsesRenderedContent() {
        var request = new TelegramTemplateSendRequest(1L, "SELECTED", List.of(7L), "T12", "Ignored", "Ignored", Map.of("name", "<friend>"), "en");
        when(renderer.render("T12", "en", request.variables())).thenReturn(new NotificationTemplateRenderer.Rendered("Rendered", "Hello &lt;friend&gt;", "TELEGRAM"));
        assertEquals(1, service.send(request, "zh"));
        verify(config).sendTelegramExternal(1L, "Rendered", "Hello &lt;friend&gt;", "-10012345678901234567", "en");
        when(renderer.render("T12", "en", request.variables())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.send(request, "zh"));
        when(renderer.render("T12", "en", request.variables())).thenReturn(new NotificationTemplateRenderer.Rendered("Internal", "Body", "INTERNAL"));
        assertThrows(BusinessException.class, () -> service.send(request, "zh"));
        verifyNoMoreInteractions(config);
    }

    @Test void saveRejectsForeignBindingAndDuplicatePairBeforeMutation() {
        when(bindings.lockChannel(1L)).thenReturn(channel(1L, "TELEGRAM", 1));
        var foreign = binding(9L, "-10012"); foreign.setChannelId(2L);
        when(bindings.lockBinding(9L)).thenReturn(foreign);
        assertThrows(BusinessException.class, () -> service.save(new TelegramBindingSaveRequest(9L, 1L, "-10012", "Name", "GROUP", 1)));
        when(bindings.findByChat(1L, "-10012")).thenReturn(binding(7L, "-10012"));
        assertThrows(BusinessException.class, () -> service.save(new TelegramBindingSaveRequest(null, 1L, "-10012", "Name", "GROUP", 1)));
        verify(bindings, never()).save(any()); verify(bindings, never()).updateById(any());
    }

    @Test void sendOnlyOptionsCannotSerializeCredentialsOrTemplateBody() throws Exception {
        NotificationChannelConfig channel = channel(1L, "TELEGRAM", 1);
        channel.setEncryptedConfig("fixture-sensitive-config"); channel.setDefaultRecipients("fixture-default");
        NotificationTemplate template = new NotificationTemplate(); template.setId(12L); template.setCode("LEGACY"); template.setName("Saved template");
        template.setChannelType("TELEGRAM"); template.setEnabled(1); template.setContentTemplate("fixture-body");
        when(channels.telegram(true)).thenReturn(List.of(channel));
        when(templates.findAllOrdered()).thenReturn(List.of(template));
        when(renderer.variables(template)).thenReturn(List.of("name"));
        when(bindings.findBindings(null, true)).thenReturn(List.of(binding(7L, "-10012")));
        String json = new ObjectMapper().writeValueAsString(service.sendOptions());
        assertTrue(json.contains("\"shortCode\":\"T12\"")); assertTrue(json.contains("\"chatId\":\"-10012\""));
        assertTrue(json.contains("\"variables\":[\"name\"]"));
        for (String value : List.of("fixture-sensitive-config", "fixture-default", "fixture-body", "encryptedConfig", "defaultRecipients")) assertFalse(json.contains(value));
    }

    @Test void settingsOnlyAccountNeedsGlobalScopeBeforeReadingSafeOptions() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "unused", List.of(new SimpleGrantedAuthority("60102"))));
        doThrow(ComputeAgentScope.denied()).when(scope).requireGlobalScope();
        assertThrows(BusinessException.class, service::sendOptions);
        verifyNoInteractions(templates);
        verify(channels, never()).telegram(anyBoolean());
        verify(bindings, never()).findBindings(null, true);
    }

    @Test void existingSendPermissionDoesNotAcquireNewGlobalScopeRequirement() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "unused", List.of(new SimpleGrantedAuthority("60702"))));
        service.sendOptions();
        verifyNoInteractions(scope);
        verify(channels).telegram(true);
    }

    @Test void savePreservesTextIdAndCreationTimeWhenEditingOrDisablingBinding() {
        when(bindings.lockChannel(1L)).thenReturn(channel(1L, "TELEGRAM", 1));
        when(bindings.save(any())).thenReturn(true);
        var created = service.save(new TelegramBindingSaveRequest(null, 1L, "-10012345678901234567", " Target ", "CHANNEL", 1));
        assertEquals("-10012345678901234567", created.getChatId());
        assertEquals("Target", created.getName());
        assertEquals(created.getCreatedAt(), created.getUpdatedAt());
        created.setId(7L);
        when(bindings.lockBinding(7L)).thenReturn(created);
        when(bindings.findByChat(1L, created.getChatId())).thenReturn(created);
        when(bindings.updateById(any())).thenReturn(true);
        var updated = service.save(new TelegramBindingSaveRequest(7L, 1L, created.getChatId(), "Renamed", "CHANNEL", 0));
        assertEquals(0, updated.getEnabled());
        assertEquals(created.getCreatedAt(), updated.getCreatedAt());
        assertEquals(created.getChatId(), updated.getChatId());
        verifyNoInteractions(config);
    }

    @Test void templatePreviewUsesTheExactSelectedIdAndDoesNotSendAnything() {
        var selected = testTemplate();
        var request = new TelegramTemplatePreviewRequest(12L, Map.of("name", "Sample"), "en");
        when(renderer.preview(selected, "en", request.variables())).thenReturn(new NotificationTemplateRenderer.Preview(
                new NotificationTemplateRenderer.Rendered("Preview", "<p>First</p><p>Second<script>bad()</script></p>", "TELEGRAM"), List.of("name")));
        var preview = service.previewTemplate(request, "zh");
        assertEquals("[TEST] Preview", preview.title());
        assertEquals("First<br>Second", preview.content());
        assertEquals(List.of("name"), preview.variables());
        verify(templates).getById(12L);
        verify(renderer).preview(selected, "en", request.variables());
        verify(renderer, never()).render(anyString(), any(), any());
        verifyNoInteractions(config, bindings, channels, scope);
    }

    @Test void singleTemplateTestCannotBroadcastAndKeepsTheSelectedBindingOnly() {
        var selected = testTemplate();
        var template = new TelegramTemplatePreviewRequest(12L, Map.of("name", "Sample"), "en");
        when(renderer.preview(selected, "en", template.variables())).thenReturn(new NotificationTemplateRenderer.Preview(
                new NotificationTemplateRenderer.Rendered("Title", "<b>Sample</b>", "TELEGRAM"), List.of("name")));
        assertEquals(1, service.testTemplate(new TelegramTemplateTestRequest(template, 1L, 7L), "zh"));
        verify(config).sendTelegramExternal(1L, "[TEST] Title", "<b>Sample</b>", "-10012345678901234567", "en");
        verifyNoInteractions(scope);
        verifyNoMoreInteractions(config);
    }

    @Test void singleTemplateTestRejectsMissingVariablesAndInvalidRecipientBeforeSending() {
        var selected = testTemplate();
        when(renderer.preview(eq(selected), eq("en"), anyMap())).thenReturn(new NotificationTemplateRenderer.Preview(
                new NotificationTemplateRenderer.Rendered("Title", "Body", "TELEGRAM"), List.of("name")));
        assertThrows(BusinessException.class, () -> service.testTemplate(new TelegramTemplateTestRequest(
                new TelegramTemplatePreviewRequest(12L, Map.of(), "en"), 1L, 7L), "en"));
        var template = new TelegramTemplatePreviewRequest(12L, Map.of("name", "Sample"), "en");
        for (Long bindingId : List.of(0L, 99L))
            assertThrows(BusinessException.class, () -> service.testTemplate(new TelegramTemplateTestRequest(template, 1L, bindingId), "en"));
        when(channels.getById(1L)).thenReturn(channel(1L, "TELEGRAM", 0));
        assertThrows(BusinessException.class, () -> service.testTemplate(new TelegramTemplateTestRequest(template, 1L, 7L), "en"));
        verifyNoInteractions(config);
    }

    @Test void templateTestRejectsDisabledForeignAndMalformedTemplates() {
        var selected = testTemplate();
        var template = new TelegramTemplatePreviewRequest(12L, Map.of(), "en");
        selected.setEnabled(0);
        assertThrows(BusinessException.class, () -> service.previewTemplate(template, "en"));
        selected.setEnabled(1); selected.setChannelType("INTERNAL");
        assertThrows(BusinessException.class, () -> service.previewTemplate(template, "en"));
        assertThrows(BusinessException.class, () -> service.previewTemplate(new TelegramTemplatePreviewRequest(12L, Map.of("bad-key", "Sample"), "en"), "en"));
        assertThrows(BusinessException.class, () -> service.previewTemplate(new TelegramTemplatePreviewRequest(12L, Map.of("name", "x".repeat(2001)), "en"), "en"));
        assertThrows(BusinessException.class, () -> service.previewTemplate(new TelegramTemplatePreviewRequest(12L, Map.of(), "invalid language"), "en"));
        verifyNoInteractions(renderer, config);
    }

    private NotificationTemplate testTemplate() {
        var selected = new NotificationTemplate(); selected.setId(12L); selected.setCode("SAVED"); selected.setChannelType("TELEGRAM"); selected.setEnabled(1);
        when(templates.getById(12L)).thenReturn(selected);
        return selected;
    }

    static NotificationChannelConfig channel(Long id, String type, int enabled) {
        var value = new NotificationChannelConfig(); value.setId(id); value.setName("Channel"); value.setChannelType(type); value.setEnabled(enabled); return value;
    }
    static NotificationTelegramBinding binding(Long id, String chatId) {
        var value = new NotificationTelegramBinding(); value.setId(id); value.setChannelId(1L); value.setChatId(chatId); value.setName("Target"); value.setChatType("GROUP"); value.setEnabled(1); return value;
    }
}
