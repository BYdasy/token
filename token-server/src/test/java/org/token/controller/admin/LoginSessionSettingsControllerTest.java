package org.token.controller.admin;

import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.auth.LoginSessionPolicy;
import org.token.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginSessionSettingsControllerTest {
    private JdbcTemplate jdbc;
    private LoginSessionSettingsController controller;
    private LocalDateTime now;
    private final JwtUserPrincipal principal = JwtUserPrincipal.builder().uid(1).username("admin").build();

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:session_settings_" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE system_setting(setting_key VARCHAR(100) PRIMARY KEY,setting_value VARCHAR(100))");
        jdbc.execute("CREATE TABLE admin_login_session(token_hash VARCHAR(64),last_active_at TIMESTAMP(6),expires_at TIMESTAMP(6),absolute_expires_at TIMESTAMP(6),revoked_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE compute_member_session(token_hash VARCHAR(64),last_seen_at TIMESTAMP(6),expires_at TIMESTAMP(6))");
        SystemSettingService settings = mock(SystemSettingService.class);
        doAnswer(call -> {
            String key = call.getArgument(0), value = call.getArgument(1);
            jdbc.update("MERGE INTO system_setting(setting_key,setting_value) KEY(setting_key) VALUES(?,?)", key, value);
            return null;
        }).when(settings).updateValue(anyString(), anyString(), anyInt());
        controller = new LoginSessionSettingsController(new org.token.service.system.LoginSessionSettingsService(new LoginSessionPolicy(jdbc), settings, new org.token.repository.system.LoginSessionSettingsRepository(org.token.repository.support.AnnotationMapperFactory.mapper(jdbc, org.token.repository.dao.system.LoginSessionSettingsMapper.class))), mock(ResultFactory.class));
        now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    void savingPolicyAdjustsActiveSessionsWithoutRevivingExpiredOrExtendingLegacyAbsoluteLimit() {
        jdbc.update("INSERT INTO admin_login_session VALUES('active',?,?,NULL,NULL)", now.minusHours(18), now.plusHours(6));
        jdbc.update("INSERT INTO admin_login_session VALUES('expired',?,?,NULL,NULL)", now.minusHours(26), now.minusHours(2));
        jdbc.update("INSERT INTO admin_login_session VALUES('revoked',?,?,NULL,?)", now.minusHours(2), now.plusHours(22), now);
        jdbc.update("INSERT INTO admin_login_session VALUES('legacy',?,?,?,NULL)", now.minusHours(2), now.plusHours(1), now.plusHours(1));
        jdbc.update("INSERT INTO compute_member_session VALUES('active',?,?)", now.minusHours(50), now.plusHours(22));
        jdbc.update("INSERT INTO compute_member_session VALUES('expired',?,?)", now.minusHours(80), now.minusHours(8));
        controller.save(new org.token.model.request.system.LoginSessionSettingsRequest(48, 96), principal);
        assertEquals(now.plusHours(30), expiry("admin_login_session", "active"));
        assertEquals(now.minusHours(2), expiry("admin_login_session", "expired"));
        assertEquals(now.plusHours(22), expiry("admin_login_session", "revoked"));
        assertEquals(now.plusHours(1), expiry("admin_login_session", "legacy"));
        assertEquals(now.plusHours(46), expiry("compute_member_session", "active"));
        assertEquals(now.minusHours(8), expiry("compute_member_session", "expired"));
        controller.save(new org.token.model.request.system.LoginSessionSettingsRequest(1, 1), principal);
        assertEquals(now.minusHours(17), expiry("admin_login_session", "active"));
        assertEquals(now.minusHours(49), expiry("compute_member_session", "active"));
        controller.save(new org.token.model.request.system.LoginSessionSettingsRequest(48, 96), principal);
        assertEquals(now.minusHours(17), expiry("admin_login_session", "active"));
        assertEquals(now.minusHours(49), expiry("compute_member_session", "active"));
    }

    @Test
    void invalidPeriodDoesNotPartiallySaveSettings() {
        assertThrows(BusinessException.class, () -> controller.save(new org.token.model.request.system.LoginSessionSettingsRequest(24, 0), principal));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_setting", Integer.class));
    }

    private LocalDateTime expiry(String table, String hash) {
        return jdbc.queryForObject("SELECT expires_at FROM " + table + " WHERE token_hash=?", LocalDateTime.class, hash);
    }
}
