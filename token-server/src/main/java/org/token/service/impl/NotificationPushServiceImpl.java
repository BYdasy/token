package org.token.service.impl;

import org.token.model.entity.SystemNotification;
import org.token.model.response.NotificationPushEvent;
import org.token.exception.BusinessException;
import org.token.service.NotificationPushService;
import org.token.tools.media.SundryUtils;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 同一账号允许多个浏览器标签同时订阅。断线由浏览器重新连接。 */
@Service
@Slf4j
public class NotificationPushServiceImpl implements NotificationPushService {

    private static final long STREAM_TIMEOUT_MS = 30 * 60_000L;
    private final ConcurrentHashMap<Integer, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SseEmitter, String> sessionDigests = new ConcurrentHashMap<>();
    private final org.token.service.auth.AdminLoginSessionService loginSessions;

    public NotificationPushServiceImpl(org.token.service.auth.AdminLoginSessionService loginSessions) {
        this.loginSessions = loginSessions;
    }

    @Override
    public SseEmitter subscribe(Integer uid) {
        if (uid == null || uid <= 0) {
            throw new BusinessException("server.user.uidRequired");
        }
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> userEmitters =
                emitters.computeIfAbsent(uid, ignored -> new CopyOnWriteArrayList<>());
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            String token = SundryUtils.getToken(servlet.getRequest());
            if (token != null && !token.isBlank()) sessionDigests.put(emitter,
                    org.token.service.auth.AdminLoginSessionService.digest(token));
        }
        userEmitters.add(emitter);
        Runnable remove = () -> remove(uid, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(ignored -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException exception) {
            remove.run();
            // 容器会处理已经失效的连接，不再次 completeWithError 触发错误分派。
            log.debug("通知流初始化时连接已断开，用户：{}", uid);
        }
        return emitter;
    }

    @Override
    public void pushMessage(SystemNotification notification) {
        if (notification == null || notification.getRecipientUid() == null) {
            return;
        }
        push(notification.getRecipientUid(), "message", new NotificationPushEvent(
                "message-" + notification.getId(),
                notification.getId(),
                notification.getTitle(),
                notification.getContent(),
                "MESSAGE",
                notification.getReadStatus(),
                notification.getCreatedAt(),
                notification.getSeverity()==null?"INFO":notification.getSeverity()
        ));
    }

    @Override
    public void pushPopup(Integer uid, String title, String htmlContent) {
        pushPopup(uid,title,htmlContent,"INFO");
    }

    @Override
    public void pushPopup(Integer uid,String title,String htmlContent,String severity) {
        if (uid == null || uid <= 0) {
            return;
        }
        String eventId = "popup-" + UUID.randomUUID();
        push(uid, "popup", new NotificationPushEvent(
                eventId,
                null,
                title,
                htmlContent == null ? "" : htmlContent,
                "POPUP",
                null,
                SundryUtils.getTime(),
                severity
        ));
    }

    private void push(Integer uid, String eventName, NotificationPushEvent event) {
        List<SseEmitter> userEmitters = emitters.get(uid);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : userEmitters) {
            if (!sessionValid(uid, emitter)) continue;
            try {
                emitter.send(SseEmitter.event().id(event.eventId()).name(eventName).data(event));
            } catch (IOException | IllegalStateException exception) {
                remove(uid, emitter);
                log.debug("通知接收连接已关闭，用户：{}", uid);
            } catch (RuntimeException exception) {
                remove(uid, emitter);
                log.error("通知推送处理失败，用户：{}", uid, exception);
                emitter.completeWithError(exception);
            }
        }
    }

    /** 注释型心跳不触发业务通知，可让代理及时发现断线并避免默认空闲连接超时。 */
    @Scheduled(fixedDelay = 20_000, initialDelay = 20_000)
    public void heartbeat() {
        emitters.forEach((uid, connections) -> connections.forEach(emitter -> {
            if (!sessionValid(uid, emitter)) return;
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException exception) {
                remove(uid, emitter);
            } catch (RuntimeException exception) {
                remove(uid, emitter);
                log.error("通知心跳处理失败，用户：{}", uid, exception);
                emitter.completeWithError(exception);
            }
        }));
    }

    /** 只推送刷新信号；在线/提款数据由浏览器另行携带当前凭据读取，调度线程不模拟管理员身份。 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void operationalStatus() {
        pulse("operational-status");
    }

    @Override
    public void financeOrders(){pulse("finance-orders");}

    private void pulse(String name) {
        emitters.forEach((uid,connections)->connections.forEach(emitter->{
            if(!sessionValid(uid,emitter))return;
            try { emitter.send(SseEmitter.event().name(name).data("refresh")); }
            catch(IOException|IllegalStateException closed) { remove(uid,emitter); }
            catch(RuntimeException failure) {
                remove(uid,emitter);
                log.error("后台状态刷新信号发送失败，用户：{}",uid,failure);
                emitter.completeWithError(failure);
            }
        }));
    }

    private boolean sessionValid(Integer uid, SseEmitter emitter) {
        String digest = sessionDigests.get(emitter);
        try {
            if (digest != null && loginSessions.validDigest(digest, uid)) return true;
        } catch (RuntimeException exception) {
            // 会话查询失败不是客户端断线；必须保留真实告警，并在无法确认身份时停止推送。
            log.error("通知连接会话校验失败，用户：{}", uid, exception);
        }
        remove(uid, emitter);
        emitter.complete();
        return false;
    }

    @PreDestroy
    public void closeStreams() {
        emitters.values().forEach(connections -> connections.forEach(SseEmitter::complete));
        emitters.clear();
        sessionDigests.clear();
    }

    private void remove(Integer uid, SseEmitter emitter) {
        sessionDigests.remove(emitter);
        CopyOnWriteArrayList<SseEmitter> userEmitters = emitters.get(uid);
        if (userEmitters == null) {
            return;
        }
        userEmitters.remove(emitter);
        if (userEmitters.isEmpty()) {
            emitters.remove(uid, userEmitters);
        }
    }
}
