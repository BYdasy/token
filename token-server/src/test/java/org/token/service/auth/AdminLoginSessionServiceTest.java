package org.token.service.auth;

import org.token.model.entity.Account;
import org.token.exception.BusinessException;
import org.token.tools.security.JWTUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminLoginSessionServiceTest {
    private JdbcTemplate jdbc;
    private AdminLoginSessionService service;
    private MutableClock clock;
    private Account account;

    static class MutableClock extends Clock {
        Instant instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:idle_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""));
        jdbc.execute("CREATE TABLE system_setting(setting_key VARCHAR(100) PRIMARY KEY,setting_value VARCHAR(100))");
        jdbc.execute("CREATE TABLE ad_master(uid INT PRIMARY KEY,password VARCHAR(100),state INT)");
        jdbc.execute("CREATE TABLE admin_login_session(token_hash VARCHAR(64) PRIMARY KEY,admin_uid INT,credential_hash VARCHAR(100),created_at TIMESTAMP(6),last_active_at TIMESTAMP(6),expires_at TIMESTAMP(6),absolute_expires_at TIMESTAMP(6),revoked_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE admin_security(admin_uid INT PRIMARY KEY,invalid_before TIMESTAMP(6),updated_at TIMESTAMP(6))");
        jdbc.update("INSERT INTO ad_master VALUES(1,'hash',1)");
        clock = new MutableClock();
        service = new AdminLoginSessionService(jdbc, new LoginSessionPolicy(jdbc), clock);
        account = new Account(); account.setUid(1); account.setUsername("admin"); account.setPassword("hash"); account.setState(1);
        ReflectionTestUtils.setField(JWTUtil.class, "jwtSecret", "isolated-idle-test-secret");
    }

    @Test
    void foregroundActivitySlidesExpiryAndKeepsSameCredential() {
        String token = service.issue(account, 123L);
        assertNotNull(JWTUtil.getSessionId(token));
        assertNull(JWTUtil.getExpiresAt(token));
        assertEquals(123L, JWTUtil.getTotpVerifiedAt(token));
        assertTrue(JWTUtil.verify(token, "admin", "1", "hash"));
        clock.advance(Duration.ofHours(23)); service.recordActivity(token, 1);
        clock.advance(Duration.ofHours(2)); assertTrue(service.valid(token, account));
        clock.advance(Duration.ofHours(22)); assertFalse(service.valid(token, account));
        service.recordActivity(token, 1); assertFalse(service.valid(token, account));
    }

    @Test
    void backgroundAuthenticationNeverRenewsOrRevivesExpiredSession() {
        String token = service.issue(account, null);
        clock.advance(Duration.ofHours(23)); assertTrue(service.valid(token, account));
        clock.advance(Duration.ofHours(1)); assertFalse(service.valid(token, account));
        assertFalse(service.validDigest(AdminLoginSessionService.digest(token), 1));
    }

    @Test
    void logoutPasswordAndDisableTakeEffectImmediately() {
        String token = service.issue(account, null);
        service.revoke(token, 1); service.recordActivity(token, 1);
        assertFalse(service.valid(token, account));
        token = service.issue(account, null); jdbc.update("UPDATE ad_master SET password='different'");
        assertFalse(service.valid(token, account));
        jdbc.update("UPDATE ad_master SET password='hash',state=0");
        assertFalse(service.valid(token, account));
    }

    @Test
    void credentialSnapshotsRemainByteExactEvenWhenDatabaseUsesCaseInsensitiveCollation() {
        jdbc.execute("ALTER TABLE ad_master ALTER COLUMN password VARCHAR_IGNORECASE(100)");
        jdbc.execute("ALTER TABLE admin_login_session ALTER COLUMN credential_hash VARCHAR_IGNORECASE(100)");
        String token = service.issue(account, null);
        assertTrue(service.valid(token, account));
        jdbc.update("UPDATE admin_login_session SET credential_hash='HASH'");
        // 验证夹具确实模拟不区分大小写的数据库规则，身份校验不能继承这种比较语义。
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ad_master a JOIN admin_login_session s ON a.uid=s.admin_uid WHERE a.password=s.credential_hash", Integer.class));
        assertFalse(service.valid(token, account));
        jdbc.update("UPDATE admin_login_session SET credential_hash='hash'");
        assertTrue(service.valid(token, account));
    }

    @Test
    void nonUtcJvmAndDatabaseDisplayZoneDoNotExpireFreshLogin() throws Exception {
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try (java.sql.Connection connection = jdbc.getDataSource().getConnection()) {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            JdbcTemplate zonedJdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            zonedJdbc.execute("SET TIME ZONE '+05:30'");
            AdminLoginSessionService zonedService = new AdminLoginSessionService(zonedJdbc, new LoginSessionPolicy(zonedJdbc), clock);
            String token = zonedService.issue(account, null);
            assertTrue(zonedService.valid(token, account));
            assertEquals(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).plusHours(24),
                    zonedJdbc.queryForObject("SELECT expires_at FROM admin_login_session", LocalDateTime.class));
        } finally { java.util.TimeZone.setDefault(previous); }
    }

    @Test
    void changedPolicyIsReadWithoutCacheAndMissingSidRowCannotBeRecreated() {
        String token = service.issue(account, null);
        clock.advance(Duration.ofHours(2));
        jdbc.update("INSERT INTO system_setting VALUES(?,?)", LoginSessionPolicy.ADMIN_KEY, "1");
        assertFalse(service.valid(token, account));
        jdbc.update("DELETE FROM admin_login_session");
        assertFalse(service.valid(token, account));
    }

    @Test
    void unregisteredJwtIsNeverAcceptedOrAutomaticallyRegistered() {
        String legacy = legacy(Duration.ofHours(2));
        assertFalse(service.valid(legacy, account));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM admin_login_session", Long.class));
        service.recordActivity(legacy, 1);
        clock.advance(Duration.ofHours(2));
        assertFalse(service.validDigest(AdminLoginSessionService.digest(legacy), 1));
        assertFalse(service.valid(legacy, account));
        clock.advance(Duration.ofHours(-2));
        service.revoke(legacy, 1); assertFalse(service.valid(legacy, account));
    }

    @Test
    void totpSecurityChangeRevokesExistingAndNotYetRegisteredOldCredentials() {
        String sid = service.issue(account, null), legacy = legacy(Duration.ofHours(5));
        clock.advance(Duration.ofSeconds(1)); service.revokeAll(1);
        assertFalse(service.valid(sid, account));
        assertFalse(service.valid(legacy, account));
        clock.advance(Duration.ofSeconds(1)); assertTrue(service.valid(service.issue(account, null), account));
    }

    @Test
    void bindingCredentialHasAbsoluteExpiryAndLogoutTombstone() {
        String binding = service.issueBinding(account);
        assertTrue(JWTUtil.isTotpBindOnly(binding));
        assertTrue(service.valid(binding, account));
        LocalDateTime expires = jdbc.queryForObject("SELECT expires_at FROM admin_login_session", LocalDateTime.class);
        service.recordActivity(binding, 1);
        assertEquals(expires, jdbc.queryForObject("SELECT expires_at FROM admin_login_session", LocalDateTime.class));
        service.revoke(binding, 1); assertFalse(service.valid(binding, account));
        String next = service.issueBinding(account);
        assertNotEquals(binding, next); assertTrue(service.valid(next, account));
        String another = service.issueBinding(account);
        assertNotEquals(next, another); assertTrue(service.valid(another, account));
        assertFalse(service.valid(next, account));
        assertEquals(Duration.ofMinutes(10), Duration.between(JWTUtil.getIssuedAt(another), JWTUtil.getExpiresAt(another)));
    }

    @Test
    void successfulLoginReplacesOnlyTheSameAccountAndOldActivityCannotRestoreIt() {
        jdbc.update("INSERT INTO ad_master VALUES(2,'another-hash',1)");
        Account other = new Account(); other.setUid(2); other.setUsername("other"); other.setPassword("another-hash"); other.setState(1);
        String otherToken = service.issue(other, null);
        String previous = service.issue(account, null);
        String current = service.issue(account, 456L);
        assertFalse(service.valid(previous, account));
        assertTrue(service.valid(current, account));
        assertTrue(service.valid(otherToken, other));
        service.recordActivity(previous, 1);
        service.revoke(previous, 1);
        assertFalse(service.valid(previous, account));
        assertTrue(service.valid(current, account));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM admin_login_session WHERE admin_uid=1 AND revoked_at IS NULL", Long.class));
    }

    @Test
    void bindingAndNormalSessionsCannotCoexistAndRevocationAllowsFreshLoginAtSameClockTick() {
        String full = service.issue(account, 123L);
        String binding = service.issueBinding(account);
        assertFalse(service.valid(full, account));
        assertTrue(service.valid(binding, account));
        service.revokeAll(1);
        assertFalse(service.valid(binding, account));
        String current = service.issue(account, 456L);
        assertTrue(service.valid(current, account));
        assertFalse(service.valid(binding, account));
        service.recordActivity(binding, 1);
        assertFalse(service.valid(binding, account));
    }

    @Test
    void lateLegacyDeviceCannotRegisterAfterNewLoginOrRestoreAfterLogout() {
        String legacy = legacy(Duration.ofHours(5));
        String current = service.issue(account, null);
        assertFalse(service.valid(legacy, account));
        assertTrue(service.valid(current, account));
        service.revoke(current, 1);
        assertFalse(service.valid(legacy, account));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM admin_login_session", Long.class));
    }

    @Test
    void olderSessionsCannotBecomeCurrentAgainEvenIfTheirRevocationIsMissing() {
        String first = service.issue(account, null);
        String second = legacy(Duration.ofHours(6));
        assertTrue(service.valid(first, account));
        assertFalse(service.valid(second, account));
        String current = service.issue(account, null);
        // 模拟升级之前遗留的未撤销行，认证依然只承认最新会话。
        jdbc.update("UPDATE admin_login_session SET revoked_at=NULL WHERE token_hash=?", AdminLoginSessionService.digest(first));
        assertFalse(service.valid(first, account));
        assertTrue(service.valid(current, account));
        service.revoke(current, 1);
        assertFalse(service.valid(first, account));
    }

    @Test
    void failedSessionPersistenceRollsBackReplacementAndKeepsPreviousSessionUsable() {
        String previous = service.issue(account, null);
        // 注入独立测试数据库约束，强制第二次 INSERT 失败而不破坏已有会话。
        jdbc.execute("CREATE UNIQUE INDEX test_only_one_history_row ON admin_login_session(admin_uid)");
        assertThrows(DataAccessException.class, () -> service.issue(account, null));
        assertTrue(service.valid(previous, account));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM admin_login_session WHERE revoked_at IS NULL", Long.class));
    }

    @Test
    void stalePasswordSnapshotAndDisabledAccountCannotReplaceExistingSession() {
        String previous = service.issue(account, null);
        Account stale = new Account(); stale.setUid(1); stale.setUsername("admin"); stale.setPassword("old-hash"); stale.setState(1);
        assertThrows(BusinessException.class, () -> service.issue(stale, null));
        assertTrue(service.valid(previous, account));
        jdbc.update("UPDATE ad_master SET state=0 WHERE uid=1");
        assertThrows(BusinessException.class, () -> service.issueBinding(account));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM admin_login_session WHERE revoked_at IS NULL", Long.class));
    }

    @Test
    void authenticationStartedBeforeSecurityRevocationCannotIssueOrBindLater() {
        long started = clock.instant().toEpochMilli();
        String previous = service.issue(account, 123L, started);
        clock.advance(Duration.ofSeconds(1));
        service.revokeAll(1);
        assertThrows(BusinessException.class, () -> service.issue(account, 456L, started));
        assertThrows(BusinessException.class, () -> service.issueBinding(account, started));
        assertFalse(service.valid(previous, account));
        clock.advance(Duration.ofMillis(1));
        String current = service.issue(account, 789L, clock.instant().toEpochMilli());
        assertTrue(service.valid(current, account));
    }

    @Test
    void concurrentLoginsAcrossServiceInstancesLeaveOnlyOneUsableSession() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6), start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 6; index++) {
                AdminLoginSessionService instance = new AdminLoginSessionService(jdbc, new LoginSessionPolicy(jdbc), clock);
                results.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return instance.issue(account, null);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> tokens = new ArrayList<>();
            for (Future<String> result : results) tokens.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(6, tokens.stream().distinct().count());
            assertEquals(1, tokens.stream().filter(token -> service.valid(token, account)).count());
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM admin_login_session WHERE revoked_at IS NULL", Long.class));
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    @Test
    void concurrentSecurityRevocationCannotLeaveAnInFlightAuthenticationActive() throws Exception {
        long started = clock.instant().toEpochMilli();
        String previous = service.issue(account, null, started);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Future<String> login = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { return service.issue(account, 123L, started); }
                catch (BusinessException rejected) { return null; }
            });
            Future<?> revoke = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                service.revokeAll(1);
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            String issued = login.get(10, TimeUnit.SECONDS);
            revoke.get(10, TimeUnit.SECONDS);
            assertFalse(service.valid(previous, account));
            if (issued != null) assertFalse(service.valid(issued, account));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM admin_login_session WHERE revoked_at IS NULL", Long.class));
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    private String legacy(Duration duration) {
        return com.auth0.jwt.JWT.create().withIssuer("EXP").withClaim("username", "admin").withClaim("uid", "1")
                .withIssuedAt(Date.from(clock.instant())).withExpiresAt(Date.from(clock.instant().plus(duration)))
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("isolated-idle-test-secret:hash"));
    }
}
