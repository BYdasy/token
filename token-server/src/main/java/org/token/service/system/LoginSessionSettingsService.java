package org.token.service.system;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.token.service.auth.LoginSessionPolicy;
import org.token.repository.system.LoginSessionSettingsRepository;
import org.token.service.SystemSettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoginSessionSettingsService {
    private final LoginSessionPolicy policy;
    private final SystemSettingService settings;
    private final LoginSessionSettingsRepository sessions;

    public Map<String, Integer> get() {
        return policy.settings();
    }

    @Transactional
    public Map<String, Integer> save(Integer adminHours, Integer userHours, Integer adminUid) {
        LoginSessionPolicy.validateHours(adminHours);
        LoginSessionPolicy.validateHours(userHours);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime oldAdminCutoff = now.minus(policy.adminIdle());
        LocalDateTime oldUserCutoff = now.minus(policy.userIdle());
        settings.updateValue(LoginSessionPolicy.ADMIN_KEY, String.valueOf(adminHours), adminUid);
        settings.updateValue(LoginSessionPolicy.USER_KEY, String.valueOf(userHours), adminUid);
        sessions.updateActiveExpiry(adminHours, userHours, now, oldAdminCutoff, oldUserCutoff);
        return policy.settings();
    }
}
