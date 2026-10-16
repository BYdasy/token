package org.token.repository.system;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.token.repository.dao.system.LoginSessionSettingsMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LoginSessionSettingsRepository {
    private final LoginSessionSettingsMapper mapper;

    /** 只重算按旧规则仍有效的会话，撤销和已过期凭据不会复活。 */
    public void updateActiveExpiry(int adminHours, int userHours, LocalDateTime now,
                                   LocalDateTime oldAdminCutoff, LocalDateTime oldUserCutoff) {
        mapper.updateActiveAdminExpiry(adminHours, now, oldAdminCutoff);
        mapper.updateActiveMemberExpiry(userHours, now, oldUserCutoff);
    }
}
