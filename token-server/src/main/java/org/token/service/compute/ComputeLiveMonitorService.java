package org.token.service.compute;

import org.token.repository.compute.ComputeLiveMonitorRepository;

import lombok.RequiredArgsConstructor;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 只读即时监控快照；权限与代理范围都在每次请求中重新判断。 */
@Service
public class ComputeLiveMonitorService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeLiveMonitorService(ComputeData data, ComputeAgentScope scope, ComputeOnlinePolicy online, ComputeLiveMonitorRepository repository) {
        this.data = data;
        this.scope = scope;
        this.online = online;
        this.repository = repository;
    }

    public ComputeLiveMonitorService(ComputeData data, ComputeAgentScope scope, ComputeOnlinePolicy online) {
        this(data, scope, online, new ComputeLiveMonitorRepository(data));
    }

    private final ComputeData data;
    private final ComputeLiveMonitorRepository repository;
    private final ComputeAgentScope scope;
    private final ComputeOnlinePolicy online;

    public Map<String,Object> settings() {
        var result=new LinkedHashMap<>(online.settings());result.put("globalScope",scope.current().global());return result;
    }
    @Transactional
    public Map<String,Object> save(Map<String,Object> input) {
        scope.requireGlobalScope();
        var settings=online.normalize(input);
        repository.saveSettingWrite(ComputeOnlinePolicy.SETTING_KEY, data.json(settings), now());
        return fields("onlineWindowMinutes",settings.get("onlineWindowMinutes"),"globalScope",true);
    }

    public Map<String,Object> status() {
        var window=online.window();
        Long members=null,withdrawals=null;
        // 与现有列表的叶子授权及旧父码兼容完全一致，无权字段保持null。
        if(allowed("20100","20101","20102","20103","20104","20105","20106")) {
            members=repository.onlineCount(window.observedAt(),window.onlineSince(),window.idleSince(),scope.queryScope());
        }
        if(allowed("30200","30201")) {
            withdrawals=repository.pendingWithdrawals(scope.queryScope());
        }
        return fields("onlineCount",members,"pendingWithdrawalCount",withdrawals,"onlineWindowMinutes",window.minutes(),
                "updatedAt",window.observedAt().toInstant(java.time.ZoneOffset.UTC).toString());
    }
    private static boolean allowed(String... codes) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!authentication.isAuthenticated())return false;
        Set<String> accepted=new HashSet<>(Arrays.asList(codes));accepted.add("super");
        return authentication.getAuthorities().stream().anyMatch(authority->accepted.contains(authority.getAuthority()));
    }
}
