package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** 仅使用隔离库；模拟事务旧快照，防止配置保存覆盖锁定后取得的新值。 */
class ComputeCatalogSettingsTest {
    private JdbcTemplate jdbc;
    private FixtureComputeData data;
    private ComputeCatalogService service;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:catalog_settings_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");

        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY)");
        data = spy(new FixtureComputeData(jdbc, new ObjectMapper()));
        service = new ComputeCatalogService(data, mock(PasswordUtils.class), mock(ComputeCurrencyService.class));
    }

    @Test void savingUsesLockedCurrentValuesInsteadOfOlderSnapshot() {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','usernameEnabled','false'),('compute','emailEnabled','true'),('compute','teamSupportedDepth','8'),('compute','addressLimit','12'),('compute','loginMethodOrder','email,username,phone')");
        doReturn(List.of(
                Map.<String, Object>of("settingKey", "usernameEnabled", "settingValue", "true"),
                Map.<String, Object>of("settingKey", "emailEnabled", "settingValue", "false"),
                Map.<String, Object>of("settingKey", "teamSupportedDepth", "settingValue", "3"),
                Map.<String, Object>of("settingKey", "addressLimit", "settingValue", "10"),
                Map.<String, Object>of("settingKey", "loginMethodOrder", "settingValue", "phone,email,username")))
                .when(data).rows("SELECT setting_key,setting_value FROM system_setting");

        service.saveSettings(Map.of("guestEnabled", false));

        assertEquals("false", stored("usernameEnabled"));
        assertEquals("true", stored("emailEnabled"));
        assertEquals("8", stored("teamSupportedDepth"));
        assertEquals("12", stored("addressLimit"));
        assertEquals("false", stored("guestEnabled"));
        assertEquals("email,username,phone", stored("loginMethodOrder"));
    }

    @Test void missingTeamSupportedDepthUsesCurrentDefault() {
        assertEquals(3, service.settings().get("teamSupportedDepth"));
        service.saveSettings(Map.of("guestEnabled", true));
        assertEquals("3", stored("teamSupportedDepth"));
    }

    @Test void loginOrderPersistsWithoutChangingEnabledOrRequiredFields() {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','usernameEnabled','true'),('compute','usernameRequired','true'),('compute','emailEnabled','false'),('compute','phoneEnabled','true')");

        var saved = service.saveSettings(Map.of("loginMethodOrder", "email,username,phone"));

        assertEquals("email,username,phone", saved.get("loginMethodOrder"));
        assertEquals("email,username,phone", stored("loginMethodOrder"));
        assertEquals("false", stored("emailEnabled"));
        assertEquals("true", stored("usernameRequired"));
        assertEquals("true", stored("phoneEnabled"));
    }

    @Test void loginOrderRejectsIncompleteDuplicateUnknownAndNonStringInputBeforeSaving() {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','loginMethodOrder','username,email,phone')");
        for (Object order : List.of("phone,email", "phone,phone,username", "phone,email,token", "phone,email,username,", "phone, email,username", List.of("phone", "email", "username"), 1)) {
            assertEquals("server.compute.v28Login.orderInvalid", assertThrows(BusinessException.class,
                    () -> service.saveSettings(Map.of("loginMethodOrder", order))).getMessage());
            assertEquals("username,email,phone", stored("loginMethodOrder"));
        }
        var request = new LinkedHashMap<String, Object>(); request.put("loginMethodOrder", null);
        assertThrows(BusinessException.class, () -> service.saveSettings(request));
    }

    @Test void missingOrMalformedStoredOrderKeepsOriginalDisplayOrder() {
        assertEquals("phone,email,username", service.settings().get("loginMethodOrder"));
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','loginMethodOrder','username,username,phone')");
        assertEquals("phone,email,username", service.settings().get("loginMethodOrder"));
        // 只读回退不改写手工配置。
        assertEquals("username,username,phone", stored("loginMethodOrder"));
    }

    private String stored(String key) {
        return jdbc.queryForObject("SELECT setting_value FROM system_setting WHERE setting_key=?", String.class, key);
    }
}
