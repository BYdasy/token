package org.token.service.impl;

import org.token.model.entity.Account;
import org.token.model.entity.SystemNotification;
import org.token.model.request.notification.NotificationSendRequest;
import org.token.model.request.notification.NotificationTargetRequest;
import org.token.model.response.TranslationCompletionResult;
import org.token.repository.support.NotificationFixture;
import org.token.repository.system.*;
import org.token.service.NotificationPushService;
import org.token.service.TranslationService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationTemplateDeliveryTest {
    @Test void manualTemplateSeverityAndPushArePublishedOnlyAfterCommit(){
        var f=new NotificationFixture();var messages=mock(SystemNotificationRepository.class);var accounts=mock(AccountRepository.class);
        var pushes=mock(NotificationPushService.class);var renderer=mock(NotificationTemplateRenderer.class);
        var account=new Account();account.setUid(9);account.setLocale("en");when(accounts.recipients(any(),any())).thenReturn(List.of(account));when(accounts.getById(9)).thenReturn(account);
        when(messages.save(any())).thenReturn(true);when(renderer.render(eq("T7"),eq("en"),any())).thenReturn(new NotificationTemplateRenderer.Rendered("Hello","<p>Safe</p>","INTERNAL"));
        var service=new NotificationServiceImpl(messages,accounts,mock(RoleRepository.class),mock(TranslationService.class),pushes,renderer);
        var request=new NotificationSendRequest(new NotificationTargetRequest("USER",List.of(9)),null,null,true,"WARNING","T7",Map.of());
        new TransactionTemplate(f.manager).executeWithoutResult(status->{assertEquals(1,service.send(request,1));verifyNoInteractions(pushes);});
        var message=org.mockito.ArgumentCaptor.forClass(SystemNotification.class);verify(pushes).pushMessage(message.capture());assertEquals("WARNING",message.getValue().getSeverity());
        verify(pushes).pushPopup(9,"Hello","<p>Safe</p>","WARNING");
        clearInvocations(pushes);
        new TransactionTemplate(f.manager).executeWithoutResult(status->{service.send(request,1);status.setRollbackOnly();});
        verifyNoInteractions(pushes);
    }
    @Test void automaticCompletionUsesSavedTemplateAndDisabledTemplateStopsPopup(){
        var messages=mock(SystemNotificationRepository.class);var accounts=mock(AccountRepository.class);var pushes=mock(NotificationPushService.class);var renderer=mock(NotificationTemplateRenderer.class);var translations=mock(TranslationService.class);
        var account=new Account();account.setUid(9);account.setLocale("en");when(accounts.getById(9)).thenReturn(account);
        when(renderer.render(eq("TR_DONE"),eq("en"),any())).thenReturn(new NotificationTemplateRenderer.Rendered("Custom title","Custom body","INTERNAL"));
        var service=new NotificationServiceImpl(messages,accounts,mock(RoleRepository.class),translations,pushes,renderer);
        var result=new TranslationCompletionResult(5,"Admin",2,1,2,2,0,1,0,1,1,1,0);
        service.notifyTranslationCompleted(result,9);verify(pushes).pushPopup(9,"Custom title","Custom body","SUCCESS");verifyNoInteractions(translations,messages);
        clearInvocations(pushes);when(renderer.render(eq("TR_DONE"),eq("en"),any())).thenReturn(null);
        service.notifyTranslationCompleted(result,9);verifyNoInteractions(pushes);
    }
}
