package org.token.service.compute;

import org.token.repository.compute.ComputeOnlineRepository;

import org.token.exception.BusinessException;
import org.token.service.auth.LoginSessionPolicy;
import org.token.repository.compute.ComputeData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 在线只取最近有效主动操作，并复核实时会话及凭据；成员列表与顶部计数共用。 */
@Service
public class ComputeOnlinePolicy {
    public static final String SETTING_KEY="liveMonitor";
    private final ComputeData data;
    private final LoginSessionPolicy sessions;
    private final Clock clock;

    @Autowired
    public ComputeOnlinePolicy(ComputeData data,LoginSessionPolicy sessions) { this(data,sessions,Clock.systemUTC()); }
    ComputeOnlinePolicy(ComputeData data,LoginSessionPolicy sessions,Clock clock) { this.data=data;this.sessions=sessions;this.clock=clock; }

    public record Window(int minutes,LocalDateTime observedAt,LocalDateTime onlineSince,LocalDateTime idleSince) { }
    public Map<String,Object> settings() {
        String value=data.setting(SETTING_KEY,"");
        if(value==null||value.isBlank())return fields("onlineWindowMinutes",10);
        try { return normalize(data.jsonMap(value)); }
        catch(RuntimeException invalid) { throw new BusinessException(503,"server.compute.v19.monitor.configurationInvalid"); }
    }
    public Map<String,Object> normalize(Map<String,Object> input) {
        if(input==null||input.size()!=1||!input.containsKey("onlineWindowMinutes")||!(input.get("onlineWindowMinutes") instanceof Number))invalid();
        int minutes;
        try { minutes=new BigDecimal(input.get("onlineWindowMinutes").toString()).intValueExact(); }
        catch(ArithmeticException|NumberFormatException failure) { invalid();return Map.of(); }
        if(minutes<1||minutes>1440)invalid();
        return fields("onlineWindowMinutes",minutes);
    }
    public Window window() {
        int minutes=((Number)settings().get("onlineWindowMinutes")).intValue();
        LocalDateTime observedAt=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        return new Window(minutes,observedAt,observedAt.minusMinutes(minutes),observedAt.minus(sessions.userIdle()));
    }
    public Set<Long> onlineMembers(Collection<Long> members) {
        return onlineMembers(members,window());
    }
    /** 同一列表的筛选、总数及行状态共用一个观察时刻，避免分页边界漂移。 */
    public Set<Long> onlineMembers(Collection<Long> members,Window window) {
        if(members.isEmpty())return Set.of();
        return new HashSet<>(new ComputeOnlineRepository(data).onlineMemberIds(members, window.observedAt(), window.onlineSince(), window.idleSince()));
    }
    private static void invalid() { throw new BusinessException("server.compute.v19.monitor.windowInvalid"); }
}
