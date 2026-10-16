package org.token.service.system;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.token.model.request.system.SessionMonitorSettingsRequest;
import org.token.service.auth.LoginSessionPolicy;
import org.token.service.compute.ComputeLiveMonitorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 登录期限和在线统计窗口的一次保存；各字段继续使用原服务的授权和校验。 */
@Service
@RequiredArgsConstructor
public class SessionMonitorSettingsService {
    private final LoginSessionSettingsService sessions;
    private final ComputeLiveMonitorService monitor;

    public Map<String, Object> get() {
        Map<String, Object> result = new LinkedHashMap<>(sessions.get());
        result.putAll(monitor.settings());
        return result;
    }

    @Transactional
    public Map<String, Object> save(SessionMonitorSettingsRequest input, Integer adminUid) {
        LoginSessionPolicy.validateHours(input.adminIdleHours());
        LoginSessionPolicy.validateHours(input.userIdleHours());
        // 监控服务实时复核全局范围；不以客户端 globalScope 作为授权依据。
        if (input.onlineWindowMinutes() != null)
            monitor.save(Map.of("onlineWindowMinutes", input.onlineWindowMinutes()));
        sessions.save(input.adminIdleHours(), input.userIdleHours(), adminUid);
        return get();
    }
}
