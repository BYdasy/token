package org.token.tools;

import org.token.util.PasswordUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 所有更新仅在独立 H2 内存数据库内执行，不读取真实配置或连接真实 MySQL。 */
class ResetDefaultAdminPasswordTest {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private static final String CONFIGURATION = """
            spring:
              datasource:
                url: ${DB_URL:jdbc:mysql://localhost:3306/token?serverTimezone=Asia/Shanghai}
                username: ${DB_USERNAME:local-test-user}
                password: ${DB_PASSWORD:test-only-secret}
            """;

    @BeforeEach
    void prepareMemoryDatabase() {
        source = new DriverManagerDataSource("jdbc:h2:mem:token;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE ad_master(uid INT PRIMARY KEY,username VARCHAR(40),password VARCHAR(100),role INT,state INT,avatar VARCHAR(100))");
        jdbc.update("INSERT INTO ad_master(uid,username,password,role,state,avatar) VALUES(1,'admin','old-test-value',7,0,'original-avatar')");
    }

    @Test
    void resetsOnlyPasswordUsingExplicitEnvironmentValue() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int result = ResetDefaultAdminPassword.run(new String[0], configuration(), Map.of("TOKEN_ADMIN_RESET_PASSWORD", "test-only-reset"),
                (url, username, password) -> source.getConnection(), new PrintStream(output), new PrintStream(errors));
        assertEquals(0, result); assertEquals("", errors.toString(StandardCharsets.UTF_8));
        String stored = jdbc.queryForObject("SELECT password FROM ad_master WHERE uid=1", String.class);
        assertTrue(new PasswordUtils().matchesPassword("test-only-reset", stored));
        assertEquals(7, jdbc.queryForObject("SELECT role FROM ad_master WHERE uid=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT state FROM ad_master WHERE uid=1", Integer.class));
        assertEquals("original-avatar", jdbc.queryForObject("SELECT avatar FROM ad_master WHERE uid=1", String.class));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(stored));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("test-only-secret"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("test-only-reset"));
    }

    @Test
    void explicitPasswordArgumentTakesPriorityOverEnvironment() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int result = ResetDefaultAdminPassword.run(new String[]{"test-only-argument"}, configuration(),
                Map.of("TOKEN_ADMIN_RESET_PASSWORD", "test-only-environment"),
                (url, username, password) -> source.getConnection(), new PrintStream(output), new PrintStream(errors));
        assertEquals(0, result); assertEquals("", errors.toString(StandardCharsets.UTF_8));
        String stored = jdbc.queryForObject("SELECT password FROM ad_master WHERE uid=1", String.class);
        assertTrue(new PasswordUtils().matchesPassword("test-only-argument", stored));
        assertFalse(new PasswordUtils().matchesPassword("test-only-environment", stored));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("test-only-argument"));
    }

    @Test
    void missingOrBlankPasswordNeverReadsConfigurationOrOpensConnection() {
        for (Map<String, String> environment : java.util.List.of(Map.<String, String>of(),
                Map.of("TOKEN_ADMIN_RESET_PASSWORD", "   "))) {
            AtomicBoolean opened = new AtomicBoolean(); ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int result = ResetDefaultAdminPassword.run(new String[0], null, environment,
                    (url, username, password) -> { opened.set(true); return source.getConnection(); },
                    System.out, new PrintStream(errors));
            assertEquals(2, result); assertFalse(opened.get());
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("TOKEN_ADMIN_RESET_PASSWORD"));
        }
        assertEquals("old-test-value", jdbc.queryForObject("SELECT password FROM ad_master WHERE uid=1", String.class));
    }

    @Test
    void invalidExplicitPasswordCannotFallBackToEnvironmentOrOpenConnection() {
        for (String password : new String[]{"", "  ", "short"}) {
            AtomicBoolean opened = new AtomicBoolean(); ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int result = ResetDefaultAdminPassword.run(new String[]{password}, configuration(),
                    Map.of("TOKEN_ADMIN_RESET_PASSWORD", "test-only-environment"),
                    (url, username, credential) -> { opened.set(true); return source.getConnection(); },
                    System.out, new PrintStream(errors));
            assertEquals(2, result); assertFalse(opened.get());
        }
        assertEquals("old-test-value", jdbc.queryForObject("SELECT password FROM ad_master WHERE uid=1", String.class));
    }

    @Test
    void rejectsDuplicateOrUnexpectedDefaultAdministratorWithoutChangingPassword() throws Exception {
        jdbc.update("INSERT INTO ad_master(uid,username,password) VALUES(2,'admin','other-test-value')");
        try (var connection = source.getConnection()) {
            assertThrows(ResetDefaultAdminPassword.ToolFailure.class, () -> ResetDefaultAdminPassword.resetPassword(connection, "test-only-reset"));
        }
        assertEquals("old-test-value", jdbc.queryForObject("SELECT password FROM ad_master WHERE uid=1", String.class));
        jdbc.update("DELETE FROM ad_master WHERE uid=1");
        try (var connection = source.getConnection()) {
            assertThrows(ResetDefaultAdminPassword.ToolFailure.class, () -> ResetDefaultAdminPassword.resetPassword(connection, "test-only-reset"));
        }
        assertEquals("other-test-value", jdbc.queryForObject("SELECT password FROM ad_master WHERE uid=2", String.class));
    }

    @Test
    void rejectsWrongActualDatabaseBeforeAnyUpdate() throws Exception {
        DriverManagerDataSource other = new DriverManagerDataSource("jdbc:h2:mem:other_database;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        try (var connection = other.getConnection()) {
            assertThrows(ResetDefaultAdminPassword.ToolFailure.class, () -> ResetDefaultAdminPassword.resetPassword(connection, "test-only-reset"));
        }
        assertEquals("old-test-value", jdbc.queryForObject("SELECT password FROM ad_master WHERE uid=1", String.class));
    }

    @Test
    void environmentOverridesConfigurationAndWrongUrlNeverOpensConnection() {
        var settings = ResetDefaultAdminPassword.readSettings(configuration(), Map.of("DB_URL", "jdbc:mysql://db:3306/token", "DB_USERNAME", "override", "DB_PASSWORD", ""));
        assertEquals("jdbc:mysql://db:3306/token", settings.url()); assertEquals("override", settings.username()); assertEquals("", settings.password());
        AtomicBoolean opened = new AtomicBoolean(); ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int result = ResetDefaultAdminPassword.run(new String[0], configuration(), Map.of("DB_URL", "jdbc:mysql://localhost:3306/exp", "TOKEN_ADMIN_RESET_PASSWORD", "test-only-reset"),
                (url, username, password) -> { opened.set(true); return source.getConnection(); }, System.out, new PrintStream(errors));
        assertEquals(2, result); assertFalse(opened.get());
    }

    @Test
    void databaseErrorsCannotLeakCredentialsOrRawDriverMessages() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int result = ResetDefaultAdminPassword.run(new String[0], configuration(), Map.of("TOKEN_ADMIN_RESET_PASSWORD", "test-only-reset"),
                (url, username, password) -> { throw new SQLException("driver detail: test-only-secret"); }, System.out, new PrintStream(errors));
        assertEquals(3, result); assertFalse(errors.toString(StandardCharsets.UTF_8).contains("test-only-secret"));
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("driver detail"));
        assertFalse(ResetDefaultAdminPassword.readSettings(configuration(), Map.of()).toString().contains("test-only-secret"));
    }

    @Test
    void rejectsNonMySqlOrOtherCatalogUrls() {
        assertDoesNotThrow(() -> ResetDefaultAdminPassword.validateDatabaseUrl("jdbc:mysql://localhost:3306/token?serverTimezone=Asia/Shanghai"));
        for (String url : new String[]{"jdbc:h2:mem:token", "jdbc:mysql://localhost:3306/token_other", "jdbc:mysql://localhost:3306/"})
            assertThrows(ResetDefaultAdminPassword.ToolFailure.class, () -> ResetDefaultAdminPassword.validateDatabaseUrl(url));
    }

    private static ByteArrayInputStream configuration() { return new ByteArrayInputStream(CONFIGURATION.getBytes(StandardCharsets.UTF_8)); }
}
