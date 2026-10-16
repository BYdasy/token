package org.token.service.compute;

import org.token.service.auth.LoginSessionPolicy;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeCredentials;
import org.token.util.PasswordUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ComputeIdleSessionTest {
    private JdbcTemplate jdbc;
    private ComputeAccountService accounts;
    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:member_idle_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""));
        jdbc.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeWorkflowIntegrationTest.utcTimestamp'");
        jdbc.execute("CREATE TABLE system_setting(setting_key VARCHAR(100) PRIMARY KEY,setting_value VARCHAR(100))");
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,username VARCHAR(100),password_hash VARCHAR(100),enabled INT)");
        jdbc.execute("CREATE TABLE compute_member_session(token_hash VARCHAR(64) PRIMARY KEY,member_id BIGINT,credential_hash VARCHAR(100),expires_at TIMESTAMP(6),last_seen_at TIMESTAMP(6))");
        jdbc.update("INSERT INTO compute_member VALUES(1,'member','hash',1)");
        accounts = new ComputeAccountService(jdbc, new PasswordUtils(), mock(ComputeBusinessService.class), mock(ComputeAgentScope.class),
                null, null, null, new LoginSessionPolicy(jdbc));
    }

    @Test
    void userDefaultsToThreeDaysAndOnlyActivityRenews() {
        String token = accounts.issueSession(1, "hash");
        LocalDateTime original = jdbc.queryForObject("SELECT expires_at FROM compute_member_session", LocalDateTime.class);
        assertTrue(original.isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(71)));
        LocalDateTime prior = LocalDateTime.now(ZoneOffset.UTC).minusHours(50).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("UPDATE compute_member_session SET last_seen_at=?,expires_at=?", prior, prior.plusHours(72));
        assertNotNull(accounts.authenticate(token));
        assertEquals(prior.withNano(prior.getNano()/1000*1000), jdbc.queryForObject("SELECT last_seen_at FROM compute_member_session", LocalDateTime.class));
        accounts.recordSessionActivity(token);
        assertTrue(jdbc.queryForObject("SELECT expires_at FROM compute_member_session", LocalDateTime.class).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(71)));
    }

    @Test
    void expiredRevokedAndChangedPasswordCannotBeRenewed() {
        String token = accounts.issueSession(1, "hash");
        jdbc.update("UPDATE compute_member_session SET expires_at=?", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        accounts.recordSessionActivity(token); assertNull(accounts.authenticate(token));
        String active = accounts.issueSession(1, "hash"); accounts.logout(active);
        accounts.recordSessionActivity(active); assertNull(accounts.authenticate(active));
        String changed = accounts.issueSession(1, "hash"); jdbc.update("UPDATE compute_member SET password_hash='changed'");
        assertNull(accounts.authenticate(changed));
    }

    @Test
    void newPolicyImmediatelyRestrictsExistingSessions() {
        String token = accounts.issueSession(1, "hash");
        jdbc.update("UPDATE compute_member_session SET last_seen_at=?", LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        jdbc.update("INSERT INTO system_setting VALUES(?,?)", LoginSessionPolicy.USER_KEY, "1");
        assertNull(accounts.authenticate(token));
    }

    @Test
    void lateActivityCannotAdvanceDisabledOrChangedCredentialSessions() {
        String token=accounts.issueSession(1,"hash");
        LocalDateTime previous=LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20).withNano(0);
        jdbc.update("UPDATE compute_member_session SET last_seen_at=?",previous);
        jdbc.update("UPDATE compute_member SET enabled=0");
        accounts.recordSessionActivity(token);
        assertEquals(previous,jdbc.queryForObject("SELECT last_seen_at FROM compute_member_session",LocalDateTime.class));
        jdbc.update("UPDATE compute_member SET enabled=1,password_hash='changed'");
        accounts.recordSessionActivity(token);
        assertEquals(previous,jdbc.queryForObject("SELECT last_seen_at FROM compute_member_session",LocalDateTime.class));
    }
}
