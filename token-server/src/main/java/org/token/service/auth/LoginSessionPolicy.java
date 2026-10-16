package org.token.service.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/** 空闲期限属于安全规则，每次从当前数据库读取，不缓存会话或期限配置。 */
@Service
public class LoginSessionPolicy {
    public static final String ADMIN_KEY = "security.admin-idle-hours";
    public static final String USER_KEY = "security.user-idle-hours";
    private final org.token.repository.system.SystemSettingRepository repository;
    @org.springframework.beans.factory.annotation.Autowired
    public LoginSessionPolicy(org.token.repository.system.SystemSettingRepository repository) { this.repository=repository; }
    public LoginSessionPolicy(JdbcTemplate jdbc) { this(new org.token.repository.system.SystemSettingRepository(jdbc)); }

    public Duration adminIdle() { return Duration.ofHours(hours(ADMIN_KEY, 24)); }
    public Duration userIdle() { return Duration.ofHours(hours(USER_KEY, 72)); }
    public Map<String, Integer> settings() { return Map.of("adminIdleHours", hours(ADMIN_KEY, 24), "userIdleHours", hours(USER_KEY, 72)); }

    private int hours(String key, int fallback) {
        var values = repository.values(key);
        if (values.isEmpty()) return fallback;
        try {
            int value = Integer.parseInt(values.get(0));
            if (value >= 1 && value <= 8760) return value;
        } catch (NumberFormatException ignored) { /* 配置异常采用保守默认期限，不接受无限会话。 */ }
        return fallback;
    }

    public static void validateHours(Integer value) {
        if (value == null || value < 1 || value > 8760) throw new BusinessException("server.session.idleHoursInvalid");
    }

    /** 后台轮询只能减少活动计时，不能通过此请求头绕过认证。 */
    public static boolean isForegroundRequest(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || Boolean.FALSE.equals(request.getAttribute("token.login.activity-success"))
                || "true".equalsIgnoreCase(request.getHeader("X-Background-Request"))) return false;
        String path = request.getRequestURI();
        return !path.equals("/api/system/notifications/my/stream")
                && !path.equals("/api/system/notifications/my/unread-count")
                && !path.equals("/api/compute/user/unread-count")
                && !path.equals("/api/compute/admin/live-status")
                && !path.equals("/api/system/time")
                && !path.equals("/api/auth/logout")
                && !path.equals("/api/compute/user/logout");
    }
}
