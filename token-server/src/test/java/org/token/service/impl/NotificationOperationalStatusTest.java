package org.token.service.impl;

import org.token.service.auth.AdminLoginSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationOperationalStatusTest {
    @SuppressWarnings("unchecked")
    @Test void periodicSignalContainsNoBusinessDataAndNeverRenewsSession() throws Exception {
        var sessions=mock(AdminLoginSessionService.class);var service=new NotificationPushServiceImpl(sessions);var emitter=mock(SseEmitter.class);
        ((Map<Integer,CopyOnWriteArrayList<SseEmitter>>)ReflectionTestUtils.getField(service,"emitters")).put(9,new CopyOnWriteArrayList<>(java.util.List.of(emitter)));
        ((Map<SseEmitter,String>)ReflectionTestUtils.getField(service,"sessionDigests")).put(emitter,"isolated-session-digest");
        when(sessions.validDigest("isolated-session-digest",9)).thenReturn(true);
        service.operationalStatus();
        var event=ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);verify(emitter).send(event.capture());
        String wire=event.getValue().build().stream().map(part->String.valueOf(part.getData())).collect(Collectors.joining());
        assertTrue(wire.contains("operational-status"));assertTrue(wire.contains("refresh"));
        assertFalse(wire.contains("onlineCount"));assertFalse(wire.contains("pendingWithdrawalCount"));
        verify(sessions,never()).recordActivity(anyString(),anyInt());
        assertEquals(30_000,NotificationPushServiceImpl.class.getMethod("operationalStatus").getAnnotation(Scheduled.class).fixedDelay());
        clearInvocations(emitter);when(sessions.validDigest("isolated-session-digest",9)).thenReturn(false);
        service.operationalStatus();verify(emitter).complete();verify(emitter,never()).send(any(SseEmitter.SseEventBuilder.class));
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(service,"emitters")).isEmpty());
    }
}
