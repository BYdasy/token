package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
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
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离库验证设置、关系锁及新UID分配；不改真实自增种子或任何存量会员。 */
public class ComputeMemberIdentitySettingsTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ComputeCatalogService catalog;
    private ComputeAccountService accounts;
    private PasswordUtils passwords;
    private ComputeMemberUidAllocator allocator;

    public static LocalDateTime utcTimestamp(int precision) { return LocalDateTime.now(ZoneOffset.UTC); }

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:identity_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source)); tx.setIsolationLevel(2);
        jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeMemberIdentitySettingsTest.utcTimestamp'");
        jdbc.execute("""
                CREATE TABLE compute_member(id BIGINT AUTO_INCREMENT PRIMARY KEY,username VARCHAR(40) UNIQUE,email VARCHAR(120) UNIQUE,
                phone VARCHAR(30),password_hash VARCHAR(120),level INT,enabled INT,invite_code VARCHAR(32) UNIQUE,inviter_id BIGINT,
                agent_id BIGINT,group_id BIGINT,pay_password_hash VARCHAR(120),user_time_zone VARCHAR(64),created_at TIMESTAMP,updated_at TIMESTAMP)
                """);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_relation_state(singleton_id INT PRIMARY KEY,version BIGINT)");
        jdbc.update("INSERT INTO compute_relation_state VALUES(1,1)");

        jdbc.execute("CREATE TABLE compute_member_session(token_hash VARCHAR(64) PRIMARY KEY,member_id BIGINT,credential_hash VARCHAR(120),expires_at TIMESTAMP,last_seen_at TIMESTAMP)");
        var scope = mock(ComputeAgentScope.class);
        when(scope.current()).thenReturn(new ComputeAgentScope.Scope(true, List.of(), true));
        when(scope.resolveInviter(isNull())).thenReturn(null);
        when(scope.relations()).thenReturn(new ComputeMemberRelations(jdbc));
        var codes = mock(ComputeInviteCodeService.class);
        when(codes.nextCode()).thenAnswer(call -> UUID.randomUUID().toString().replace("-", ""));
        passwords = spy(new PasswordUtils());
        allocator = new ComputeMemberUidAllocator(jdbc, bound -> bound - 1);
        accounts = new ComputeAccountService(jdbc, passwords, mock(ComputeBusinessService.class), scope, null, null, null, null, codes, allocator);
        catalog = new ComputeCatalogService(new FixtureComputeData(jdbc, new ObjectMapper()), passwords, mock(ComputeCurrencyService.class), null, null, allocator);
    }

    private Map<String, Object> save(Map<String, Object> input) { return tx.execute(status -> catalog.saveSettings(input)); }
    private long admin(String username) {
        return tx.execute(status -> accounts.createFromAdmin(new ComputeMemberCreateRequest(username, null, null, "abcdef", null, true))).id();
    }
    private long register(String username) {
        var result = tx.execute(status -> accounts.register(new ComputeAuthRequests.Register(username, null, null, "abcdef", null)));
        return ((Number) ((Map<?, ?>) result.get("member")).get("id")).longValue();
    }
    private long cursor() { return Long.parseLong(jdbc.queryForObject("SELECT setting_value FROM system_setting WHERE setting_key=?", String.class, ComputeMemberUidAllocator.CURSOR)); }
    private void reject(String key, Runnable action) { assertEquals(key, assertThrows(BusinessException.class, action::run).getMessage()); }

    @Test void defaultsPreserveAutoIncrementAndRecordSuccessfulHighWatermark() {
        var defaults = catalog.settings();
        assertEquals(true, defaults.get(ComputeMemberUidAllocator.USERNAME_LIMIT));
        assertNull(defaults.get(ComputeMemberUidAllocator.BASE)); assertEquals(1, defaults.get(ComputeMemberUidAllocator.STEP));
        assertEquals(1, register("first")); assertEquals(2, admin("second")); assertEquals(2, cursor());
        save(Map.of(ComputeMemberUidAllocator.STEP, 3));
        assertEquals(3, admin("third")); assertEquals(3, cursor());
        assertNull(catalog.settings().get(ComputeMemberUidAllocator.BASE));
    }

    @Test void disabledCharacterLimitAllowsAllCharacterKindsButKeepsLengthNormalizationAndUniqueness() {
        save(Map.of(ComputeMemberUidAllocator.USERNAME_LIMIT, false));
        for (String value : List.of("العربية", "中文会员", "ab cd", "ab.@😀", "Kelvin")) register(value);
        long id = admin(" 中文后台 ");
        assertEquals("中文后台", jdbc.queryForObject("SELECT username FROM compute_member WHERE id=?", String.class, id));
        reject("server.compute.account.invalid", () -> register(" a "));
        reject("server.compute.account.invalid", () -> admin("中".repeat(41)));
        reject("server.compute.account.exists", () -> register("KELVIN"));
        save(Map.of(ComputeMemberUidAllocator.USERNAME_LIMIT, true));
        reject("server.compute.username.asciiRule", () -> admin("中文新号"));
        var login = tx.execute(status -> accounts.login("username", new ComputeAuthRequests.Login("العربية", "abcdef")));
        assertEquals("العربية", ((Map<?, ?>) login.get("member")).get("username"));
    }

    @Test void publicAndAdminCreationShareRandomSequenceAndRepeatedBaseNeverRestartsIt() {
        long old = admin("existing");
        save(Map.of(ComputeMemberUidAllocator.BASE, 200, ComputeMemberUidAllocator.STEP, 3));
        assertEquals(203, register("newpublic")); assertEquals(206, admin("newadmin"));
        save(Map.of(ComputeMemberUidAllocator.BASE, 200, ComputeMemberUidAllocator.STEP, 1));
        assertEquals(207, register("continued")); assertEquals(207, cursor());
        assertEquals("existing", jdbc.queryForObject("SELECT username FROM compute_member WHERE id=?", String.class, old));
        assertEquals(207L, catalog.settings().get("memberUidCurrentMax"));
        assertEquals(207L, catalog.settings().get("memberUidHighWatermark"));
    }

    @Test void highWatermarkIncludesDeletedIdsAndUnconfiguredDatabaseAutoIncrement() {
        jdbc.execute("ALTER TABLE compute_member ALTER COLUMN id RESTART WITH 500");
        assertEquals(499L, catalog.settings().get("memberUidHighWatermark"));
        reject("server.compute.memberUid.baseTooSmall", () -> save(Map.of(ComputeMemberUidAllocator.BASE, 499)));
        save(Map.of(ComputeMemberUidAllocator.BASE, 600));
        long created = admin("higher"); assertEquals(601, created);
        jdbc.update("DELETE FROM compute_member WHERE id=?", created);
        assertEquals(0L, catalog.settings().get("memberUidCurrentMax"));
        assertEquals(601L, catalog.settings().get("memberUidHighWatermark"));
        reject("server.compute.memberUid.baseTooSmall", () -> save(Map.of(ComputeMemberUidAllocator.BASE, 601)));
        save(Map.of(ComputeMemberUidAllocator.BASE, 700)); assertEquals(701, register("afterdeleted"));
    }

    @Test void rolledBackCreationDoesNotAdvanceCommittedCursorOrEnlargeNextRandomStep() {
        save(Map.of(ComputeMemberUidAllocator.BASE, 200, ComputeMemberUidAllocator.STEP, 3));
        tx.executeWithoutResult(status -> {
            assertEquals(203, accounts.createFromAdmin(new ComputeMemberCreateRequest("rollback", null, null, "abcdef", null, true)).id());
            status.setRollbackOnly();
        });
        assertEquals(0, cursor()); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member", Integer.class));
        assertEquals(203, admin("committed")); assertEquals(203, cursor());
    }

    @Test void invalidOrReadOnlyConfigurationIsRejectedWithoutChangingStoredPolicy() {
        for (Object flag : List.of("false", 0, "true"))
            reject("server.compute.invalidInput", () -> save(Map.of(ComputeMemberUidAllocator.USERNAME_LIMIT, flag)));
        for (Object value : List.of(0, -1, 1.1, "2.0", "abc", "9007199254740991"))
            reject("server.compute.memberUid.ruleInvalid", () -> save(Map.of(ComputeMemberUidAllocator.BASE, value)));
        for (Object value : List.of(0, -1, 1.1, 1000001))
            reject("server.compute.memberUid.ruleInvalid", () -> save(Map.of(ComputeMemberUidAllocator.STEP, value)));
        reject("server.compute.invalidInput", () -> save(Map.of(ComputeMemberUidAllocator.CURSOR, 500)));
        reject("server.compute.invalidInput", () -> save(Map.of("memberUidHighWatermark", 500)));
        reject("server.compute.memberUid.exhausted", () -> save(Map.of(ComputeMemberUidAllocator.BASE, ComputeMemberUidAllocator.MAX_UID - 1, ComputeMemberUidAllocator.STEP, 2)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_setting", Integer.class));
    }

    @Test void concurrentRegistrationsAndAdminCreationsNeverReuseAnId() throws Exception {
        save(Map.of(ComputeMemberUidAllocator.BASE, 200, ComputeMemberUidAllocator.STEP, 3));
        var pool = Executors.newFixedThreadPool(6); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 12; i++) {
                int index = i;
                futures.add(pool.submit(() -> { start.await(); return index % 2 == 0 ? register("public" + index) : admin("admin" + index); }));
            }
            start.countDown(); var ids = new TreeSet<Long>();
            for (var future : futures) assertTrue(ids.add(future.get(15, TimeUnit.SECONDS)));
            long previous = 200;
            for (long id : ids) { assertEquals(3, id - previous); previous = id; }
            assertEquals(previous, cursor()); assertEquals(12, ids.size());
        } finally { pool.shutdownNow(); }
    }

    @Test void waitingRegistrationRechecksNewCharacterSwitchAndUidRulesInsideRelationLock() throws Exception {
        save(Map.of(ComputeMemberUidAllocator.USERNAME_LIMIT, false));
        var reached = new CountDownLatch(1); var resume = new CountDownLatch(1);
        doAnswer(call -> { reached.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)); return call.callRealMethod(); }).when(passwords).hashPassword(anyString());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var blocked = pool.submit(() -> assertThrows(BusinessException.class, () -> register("中文会员")));
            assertTrue(reached.await(5, TimeUnit.SECONDS));
            save(Map.of(ComputeMemberUidAllocator.USERNAME_LIMIT, true, ComputeMemberUidAllocator.BASE, 500));
            resume.countDown();
            assertEquals("server.compute.username.asciiRule", blocked.get(5, TimeUnit.SECONDS).getMessage());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member", Integer.class));
            assertEquals(501, admin("ascii_next"));
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    @Test void failedSettingsTransactionRestoresBothCharacterSwitchAndUidRule() {
        tx.executeWithoutResult(status -> {
            catalog.saveSettings(Map.of(ComputeMemberUidAllocator.USERNAME_LIMIT, false, ComputeMemberUidAllocator.BASE, 200, ComputeMemberUidAllocator.STEP, 3));
            status.setRollbackOnly();
        });
        assertEquals(true, catalog.settings().get(ComputeMemberUidAllocator.USERNAME_LIMIT));
        assertNull(catalog.settings().get(ComputeMemberUidAllocator.BASE));
        assertEquals(1, register("still_auto"));
    }

    @Test void unsafeAutoIncrementIdRollsBackMemberAndDoesNotPublishCursor() {
        jdbc.execute("ALTER TABLE compute_member ALTER COLUMN id RESTART WITH " + (ComputeMemberUidAllocator.MAX_UID + 1));
        reject("server.compute.memberUid.exhausted", () -> admin("unsafe_uid"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_setting WHERE setting_key=?", Integer.class, ComputeMemberUidAllocator.CURSOR));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session", Integer.class));
    }

    @Test void firstCursorCreationAndUnrelatedSettingsSavesUseCompatibleLockOrder() throws Exception {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','usernameCharacterLimitEnabled','true')");
        var pool = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                int index = i;
                futures.add(pool.submit(() -> { start.await(); return register("parallel" + index); }));
                futures.add(pool.submit(() -> { start.await(); return save(Map.of("guestEnabled", index % 2 == 0)); }));
            }
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
            assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member", Integer.class));
            assertEquals(8, cursor());
        } finally { pool.shutdownNow(); }
    }
}
