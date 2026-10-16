package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.model.request.compute.ComputeAuthRequests;
import org.token.model.request.compute.ComputeMemberCreateRequest;
import org.token.service.compute.ComputeAgentScope;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离验证会员登录名的新写入规则；不修改旧账号，不调用资金或外部通知。 */
public class ComputeUsernamePolicyTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ComputeAccountService accounts;
    private ComputeBusinessService business;
    private ComputeInviteCodeService codes;
    private static final String PASSWORD = "abc12345";

    public static LocalDateTime utcTimestamp(int precision) { return LocalDateTime.now(ZoneOffset.UTC); }

    @BeforeEach void prepare() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:username_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(2);
        jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeUsernamePolicyTest.utcTimestamp'");
        jdbc.execute("""
                CREATE TABLE compute_member(id BIGINT AUTO_INCREMENT PRIMARY KEY,username VARCHAR(40) UNIQUE,
                    email VARCHAR(120) UNIQUE,phone VARCHAR(30) UNIQUE,password_hash VARCHAR(120),level INT,enabled INT,
                    invite_code VARCHAR(32) UNIQUE,inviter_id BIGINT,agent_id BIGINT,group_id BIGINT,
                    pay_password_hash VARCHAR(120),user_time_zone VARCHAR(64),created_at DATETIME(6),updated_at DATETIME(6))
                """);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_member_session(token_hash VARCHAR(64) PRIMARY KEY,member_id BIGINT,credential_hash VARCHAR(120),expires_at DATETIME(6),last_seen_at DATETIME(6))");
        jdbc.execute("CREATE TABLE compute_recovery_request(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT,method VARCHAR(20),account VARCHAR(120),status VARCHAR(20),created_at DATETIME(6))");
        var scope = mock(ComputeAgentScope.class);
        when(scope.current()).thenReturn(new ComputeAgentScope.Scope(true, List.of(), true));
        when(scope.relations()).thenReturn(mock(ComputeMemberRelations.class));
        codes = mock(ComputeInviteCodeService.class);
        when(codes.nextCode()).thenAnswer(call -> UUID.randomUUID().toString().replace("-", ""));
        business = mock(ComputeBusinessService.class);
        accounts = new ComputeAccountService(jdbc, new PasswordUtils(), business, scope, null, null, null, null, codes);
    }

    private long admin(String username) {
        return tx.execute(status -> accounts.createFromAdmin(
                new ComputeMemberCreateRequest(username, null, null, PASSWORD, null, true))).id();
    }

    private Map<String, Object> register(String username) {
        return tx.execute(status -> accounts.register(new ComputeAuthRequests.Register(username, null, null, PASSWORD, null)));
    }

    private void rejects(String expected, Runnable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action::run).getMessage());
    }

    @Test void publicAndAdminCreationRejectUnicodeAndOtherCharactersBeforeAnyWrite() {
        for (String value : List.of("中文会员", "العربية", "１２３４", "١٢٣٤", "ab.cd", "ab@cd", "user name", "ab😀cd", "éabc", "Kelvin")) {
            rejects("server.compute.username.asciiRule", () -> register(value));
            rejects("server.compute.username.asciiRule", () -> admin(value));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session", Integer.class));
        verifyNoInteractions(business);
    }

    @Test void creationKeepsThreeToFortyCharactersAndExistingTrimLowercaseNormalization() {
        var created = register(" A_- ");
        assertEquals("a_-", ((Map<?, ?>) created.get("member")).get("username"));
        long id = admin("Z".repeat(38) + "9_");
        assertEquals("z".repeat(38) + "9_", jdbc.queryForObject("SELECT username FROM compute_member WHERE id=?", String.class, id));
        for (String value : List.of("ab", "a".repeat(41))) {
            rejects("server.compute.account.invalid", () -> register(value));
            rejects("server.compute.account.invalid", () -> admin(value));
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member", Integer.class));
    }

    @Test void optionalUsernameRegistrationStillUsesConfiguredEmailIdentity() {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','usernameEnabled','false'),('compute','usernameRequired','false'),('compute','emailRequired','true')");
        var registered = tx.execute(status -> accounts.register(
                new ComputeAuthRequests.Register(null, "Mail@Example.invalid", null, PASSWORD, null)));
        Map<?, ?> member = (Map<?, ?>) registered.get("member");
        assertNull(member.get("username"));
        assertEquals("mail@example.invalid", member.get("email"));
    }

    @Test void compatibilityProfileWriterRejectsUnicodeBeforeLowercaseAndKeepsItsLengthRule() {
        long id = admin("original");
        for (String value : List.of("中文会员", "العربية", "Kelvin", "abc.def"))
            rejects("server.compute.username.asciiRule", () -> tx.execute(status -> accounts.updateProfile(id,
                    new ComputeAuthRequests.Profile(value, null, null))));
        for (String value : List.of("abc", "a".repeat(41)))
            rejects("server.compute.account.invalid", () -> tx.execute(status -> accounts.updateProfile(id,
                    new ComputeAuthRequests.Profile(value, null, null))));
        assertEquals("original", jdbc.queryForObject("SELECT username FROM compute_member WHERE id=?", String.class, id));
        var updated = tx.execute(status -> accounts.updateProfile(id, new ComputeAuthRequests.Profile(" New_9-A ", null, null)));
        assertEquals("new_9-a", updated.get("username"));
    }

    @Test void legacyUnicodeLoginRecoveryAndUnrelatedProfileChangesRemainCompatible() {
        long id = admin("legacy");
        jdbc.update("UPDATE compute_member SET username=? WHERE id=?", "العربية", id);
        var login = tx.execute(status -> accounts.login("username", new ComputeAuthRequests.Login("العربية", PASSWORD)));
        assertEquals("العربية", ((Map<?, ?>) login.get("member")).get("username"));
        tx.executeWithoutResult(status -> accounts.requestRecovery(new ComputeAuthRequests.Recovery("username", "العربية")));
        assertEquals(id, jdbc.queryForObject("SELECT member_id FROM compute_recovery_request", Long.class));
        var updated = tx.execute(status -> accounts.updateProfile(id, new ComputeAuthRequests.Profile(null, "old@example.invalid", null)));
        assertEquals("العربية", updated.get("username"));
        assertNotNull(accounts.authenticate((String) login.get("token")));
    }

    @Test void duplicateCreationAndProfileRenameReturnExistingIdentityErrorWithoutChangingMembers() {
        long first = admin("Some_User");
        rejects("server.compute.account.exists", () -> register("SOME_USER"));
        rejects("server.compute.account.exists", () -> admin("some_user"));
        long second = admin("other_user");
        rejects("server.compute.account.exists", () -> tx.execute(status -> accounts.updateProfile(second,
                new ComputeAuthRequests.Profile("SoMe_UsEr", null, null))));
        assertEquals("some_user", jdbc.queryForObject("SELECT username FROM compute_member WHERE id=?", String.class, first));
        assertEquals("other_user", jdbc.queryForObject("SELECT username FROM compute_member WHERE id=?", String.class, second));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session", Integer.class));
    }

    @Test void concurrentCaseVariantsHaveExactlyOneWinnerAndOneFriendlyDuplicate() throws Exception {
        // 不依靠前置查重；让两笔事务同时到达INSERT，验证唯一约束及重复异常转换。
        var barrier = new CyclicBarrier(2);
        when(codes.nextCode()).thenAnswer(call -> { barrier.await(5, TimeUnit.SECONDS); return UUID.randomUUID().toString().replace("-", ""); });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.of("Concurrent_9", "CONCURRENT_9").stream().map(username -> pool.submit(() -> {
                try { admin(username); return "created"; }
                catch (BusinessException exception) { return exception.getMessage(); }
            })).toList();
            int created = 0, duplicate = 0;
            for (var task : tasks) {
                String result = task.get(10, TimeUnit.SECONDS);
                if ("created".equals(result)) created++;
                else { assertEquals("server.compute.account.exists", result); duplicate++; }
            }
            assertEquals(1, created); assertEquals(1, duplicate);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member WHERE username='concurrent_9'", Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
