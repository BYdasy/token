package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.model.request.compute.ComputeAuthRequests;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeCredentials;
import org.token.util.PasswordUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 隔离数据库验证会话替换、并发登录及失败回滚，不连接实际账号或发送通知。 */
class ComputeSingleSessionTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ComputeAccountService accounts;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:single_member_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeWorkflowIntegrationTest.utcTimestamp'");
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("""
                CREATE TABLE compute_member(id BIGINT PRIMARY KEY,username VARCHAR(40),email VARCHAR(120),phone VARCHAR(24),
                    password_hash VARCHAR(100),pay_password_hash VARCHAR(100),enabled INT,level INT,invite_code VARCHAR(32),
                    user_time_zone VARCHAR(64),created_at TIMESTAMP(6),updated_at TIMESTAMP(6))
                """);
        jdbc.execute("""
                CREATE TABLE compute_member_session(token_hash VARCHAR(64) PRIMARY KEY,member_id BIGINT,
                    credential_hash VARCHAR(100),expires_at TIMESTAMP(6),last_seen_at TIMESTAMP(6))
                """);
        jdbc.update("INSERT INTO compute_member(id,username,password_hash,enabled,level,created_at) VALUES(1,'member','hash',1,1,CURRENT_TIMESTAMP),(2,'other','hash',1,1,CURRENT_TIMESTAMP)");
        PasswordUtils passwords = mock(PasswordUtils.class);
        when(passwords.hashPassword("new-password")).thenReturn("new-hash");
        when(passwords.matchesPassword(anyString(), anyString())).thenAnswer(invocation ->
                "correct-password".equals(invocation.getArgument(0)) && "hash".equals(invocation.getArgument(1)));
        accounts = new ComputeAccountService(jdbc, passwords, mock(ComputeBusinessService.class), mock(ComputeAgentScope.class));
    }

    private String issue(long memberId) {
        return tx.execute(status -> accounts.issueSession(memberId, "hash"));
    }

    private String login(String password) {
        return tx.execute(status -> accounts.login("username", new ComputeAuthRequests.Login("member", password)))
                .get("token").toString();
    }

    @Test
    void successfulLoginReplacesOnlyThatMemberAndLateOldRequestsCannotChangeNewSession() {
        String old = issue(1);
        String other = issue(2);
        String current = login("correct-password");
        assertNull(accounts.authenticate(old));
        assertNotNull(accounts.authenticate(current));
        assertNotNull(accounts.authenticate(other));
        var before = jdbc.queryForMap("SELECT * FROM compute_member_session WHERE token_hash=?", ComputeCredentials.digest(current));

        accounts.recordSessionActivity(old);
        accounts.logout(old);

        assertEquals(before, jdbc.queryForMap("SELECT * FROM compute_member_session WHERE token_hash=?", ComputeCredentials.digest(current)));
        assertNotNull(accounts.authenticate(current));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session WHERE member_id=1", Integer.class));
    }

    @Test
    void wrongPasswordDoesNotDisplaceCurrentSession() {
        String current = issue(1);
        BusinessException failure = assertThrows(BusinessException.class, () -> login("wrong-password"));
        assertEquals("server.compute.login.failed", failure.getMessage());
        assertNotNull(accounts.authenticate(current));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session", Integer.class));
    }

    @Test
    void existingMultipleValidSessionsAreBothRejectedUntilANewLoginReplacesThem() {
        String first = issue(1);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        historicalSession("another-device", "hash", now.plusHours(72), now);
        assertNull(accounts.authenticate(first));
        assertNull(accounts.authenticate("another-device"));

        String replacement = login("correct-password");
        assertNotNull(accounts.authenticate(replacement));
        assertNull(accounts.authenticate(first));
        assertNull(accounts.authenticate("another-device"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session", Integer.class));
    }

    @Test
    void expiredIdleAndOldCredentialRowsDoNotBlockASingleCurrentSession() {
        String current = issue(1);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        historicalSession("old-credential", "old-hash", now.plusHours(72), now);
        historicalSession("expired", "hash", now.minusMinutes(1), now.minusMinutes(1));
        historicalSession("idle", "hash", now.plusHours(1), now.minusHours(73));

        assertNotNull(accounts.authenticate(current));
        assertNull(accounts.authenticate("old-credential"));
        assertNull(accounts.authenticate("expired"));
        assertNull(accounts.authenticate("idle"));
    }

    private void historicalSession(String token, String credential, LocalDateTime expiry, LocalDateTime lastSeen) {
        jdbc.update("INSERT INTO compute_member_session VALUES(?,?,?,?,?)",
                ComputeCredentials.digest(token), 1, credential, expiry, lastSeen);
    }

    @Test
    void changedCredentialsCannotReplaceCurrentSession() {
        String current = issue(1);
        assertThrows(BusinessException.class, () -> tx.execute(status -> accounts.issueSession(1, "stale-hash")));
        assertNotNull(accounts.authenticate(current));
        assertThrows(BusinessException.class, () -> tx.execute(status -> accounts.issueSession(99, "hash")));
        jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=1");
        assertThrows(BusinessException.class, () -> issue(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session", Integer.class));
    }

    @Test
    void simultaneousLoginsLeaveExactlyOneUsableSession() throws Exception {
        String old = issue(1);
        var executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 3; i++) results.add(executor.submit(() -> {
                start.await();
                return login("correct-password");
            }));
            start.countDown();
            var issued = new ArrayList<String>();
            for (var result : results) issued.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(1, issued.stream().filter(token -> accounts.authenticate(token) != null).count());
            assertNull(accounts.authenticate(old));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session WHERE member_id=1", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void insertFailureRollsBackSessionReplacement() {
        String current = issue(1);
        // 保留旧记录，但令后续插入触发数据库约束失败，确认删除不会单独提交。
        jdbc.execute("ALTER TABLE compute_member_session ADD CONSTRAINT reject_new_token CHECK(token_hash='"
                + ComputeCredentials.digest(current) + "')");
        assertThrows(DataIntegrityViolationException.class, () -> login("correct-password"));
        assertNotNull(accounts.authenticate(current));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session", Integer.class));
    }

    @Test
    void passwordChangeSerializesWithPendingLoginAndStaleCredentialsCannotRestoreSession() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch issued = new CountDownLatch(1), releaseLogin = new CountDownLatch(1), changeStarted = new CountDownLatch(1);
        try {
            var pendingLogin = executor.submit(() -> tx.execute(status -> {
                String token = accounts.issueSession(1, "hash");
                issued.countDown();
                await(releaseLogin);
                return token;
            }));
            assertTrue(issued.await(10, TimeUnit.SECONDS));
            var change = executor.submit(() -> {
                changeStarted.countDown();
                tx.executeWithoutResult(status -> accounts.changePassword(1,
                        new ComputeAuthRequests.Password("correct-password", "new-password")));
            });
            assertTrue(changeStarted.await(10, TimeUnit.SECONDS));
            releaseLogin.countDown();
            String token = pendingLogin.get(10, TimeUnit.SECONDS);
            change.get(10, TimeUnit.SECONDS);
            assertNull(accounts.authenticate(token));
            assertThrows(BusinessException.class, () -> issue(1));
            accounts.recordSessionActivity(token);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session", Integer.class));
        } finally {
            releaseLogin.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("会话并发测试等待超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
