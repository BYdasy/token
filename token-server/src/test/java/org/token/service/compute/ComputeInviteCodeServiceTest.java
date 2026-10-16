package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.model.security.JwtUserPrincipal;
import org.token.model.request.compute.ComputeAuthRequests;
import org.token.model.request.compute.ComputeMemberCreateRequest;
import org.token.service.compute.ComputeAgentScope;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立H2验证永久废弃、配置、事务和并发；不调用真实支付、通知或数据库。 */
public class ComputeInviteCodeServiceTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    ComputeAgentScope scope;
    ComputeInviteCodeService codes;

    public static LocalDateTime utcTimestamp(int precision) { return LocalDateTime.now(ZoneOffset.UTC); }

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:invite_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source)); tx.setIsolationLevel(2);
        jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeInviteCodeServiceTest.utcTimestamp'");
        jdbc.execute("""
                CREATE TABLE compute_member(id BIGINT AUTO_INCREMENT PRIMARY KEY,username VARCHAR(40) UNIQUE,email VARCHAR(120),phone VARCHAR(30),
                password_hash VARCHAR(120),level INT,enabled INT,invite_code VARCHAR(32) UNIQUE,inviter_id BIGINT,group_id BIGINT,
                invitation_enabled INT DEFAULT 1,pay_password_hash VARCHAR(120),user_time_zone VARCHAR(64),created_at DATETIME(6),updated_at DATETIME(6))
                """);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_relation_state(singleton_id INT PRIMARY KEY,version BIGINT)");
        jdbc.execute("CREATE TABLE compute_admin_member_scope(admin_uid INT,root_member_id BIGINT)");
        jdbc.execute("CREATE TABLE ad_master(uid INT PRIMARY KEY,scope_configured_at DATETIME(6))");
        jdbc.update("INSERT INTO ad_master(uid) VALUES(1)");
        jdbc.execute("CREATE TABLE compute_member_session(token_hash VARCHAR(64) PRIMARY KEY,member_id BIGINT,credential_hash VARCHAR(120),expires_at DATETIME(6),last_seen_at DATETIME(6))");
        jdbc.update("INSERT INTO compute_relation_state VALUES(1,1)");
        jdbc.update("INSERT INTO compute_member(id,username,enabled,invite_code,level,created_at,updated_at) VALUES(1,'parent',1,'Original-_a1',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_member(id,username,enabled,invite_code,level,inviter_id,created_at,updated_at) VALUES(2,'child',1,'Original-b2',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        TokenTestSchema.inviteCodes(jdbc);
        scope = new ComputeAgentScope(jdbc); codes = new ComputeInviteCodeService(jdbc, scope); admin("super");
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    void admin(String authority) {
        var principal = JwtUserPrincipal.builder().uid(1).username("operator").state(1).authorities(Set.of(authority)).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    Map<String, Object> request(String current, String requestNo) { return Map.of("expectedInviteCode", current, "requestNo", requestNo); }
    Map<String, Object> rotate(String current, String requestNo) { return tx.execute(status -> codes.rotate(1, request(current, requestNo))); }
    String current() { return jdbc.queryForObject("SELECT invite_code FROM compute_member WHERE id=1", String.class); }

    @Test void validatesModesAndLengthWithoutTruncatingFractions() {
        assertEquals(new ComputeInviteCodeService.Policy("ALPHANUMERIC", 12), ComputeInviteCodeService.policy(null, null));
        for (String mode : List.of("DIGITS", "LETTERS", "ALPHANUMERIC")) {
            assertEquals(6, ComputeInviteCodeService.policy(mode, 6).length());
            assertEquals(32, ComputeInviteCodeService.policy(mode, 32).length());
        }
        for (Object invalid : List.of(5, 33, 6.1, "6.0", "", "abc"))
            assertThrows(BusinessException.class, () -> ComputeInviteCodeService.policy("DIGITS", invalid));
        assertThrows(BusinessException.class, () -> ComputeInviteCodeService.policy("OTHER", 6));
    }

    @Test void generatesExactModeAndBothCharacterKindsInCombination() {
        for (String mode : List.of("DIGITS", "LETTERS", "ALPHANUMERIC")) {
            jdbc.update("MERGE INTO system_setting(group_name,setting_key,setting_value) KEY(setting_key) VALUES('compute','inviteCodeMode',?)", mode);
            jdbc.update("MERGE INTO system_setting(group_name,setting_key,setting_value) KEY(setting_key) VALUES('compute','inviteCodeLength','6')");
            for (int i = 0; i < 30; i++) {
                String code = tx.execute(status -> { scope.relations().lock(); return codes.nextCode(); });
                assertNotNull(code); assertEquals(6, code.length());
                assertTrue(code.matches("DIGITS".equals(mode) ? "[0-9]+" : "LETTERS".equals(mode) ? "[A-Z]+" : "(?=.*[0-9])(?=.*[A-Z])[A-Z0-9]+"));
            }
        }
    }

    @Test void settingsSaveValidatesBeforeWritingAndExposesConfiguredValues() {

        var data = new org.token.repository.compute.ComputeData(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        var catalog = new ComputeCatalogService(data, new PasswordUtils());
        assertEquals(12, catalog.settings().get("inviteCodeLength"));
        assertThrows(BusinessException.class, () -> tx.execute(status -> catalog.saveSettings(Map.of("inviteCodeMode", "DIGITS", "inviteCodeLength", 5))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_setting", Integer.class));
        var saved = tx.execute(status -> catalog.saveSettings(Map.of("inviteCodeMode", "LETTERS", "inviteCodeLength", 32)));
        assertEquals("LETTERS", saved.get("inviteCodeMode")); assertEquals(32, saved.get("inviteCodeLength"));
    }

    @Test void rotationUsesMemberEditPermissionAndHasOperationAudit() throws Exception {
        var method = org.token.controller.admin.ComputeManagementController.class.getDeclaredMethod("rotateInviteCode", long.class, Map.class);
        assertNotNull(method.getAnnotation(org.token.custom.LogAction.class));
        var expression = new org.springframework.expression.spel.standard.SpelExpressionParser()
                .parseExpression(method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
        for (String authority : List.of("20102", "super", "610042", "20106", "20100")) {
            var authentication = new UsernamePasswordAuthenticationToken("operator", null, List.of(new SimpleGrantedAuthority(authority)));
            var context = new org.springframework.expression.spel.support.StandardEvaluationContext(new org.springframework.security.access.expression.SecurityExpressionRoot(authentication) {});
            assertEquals(Set.of("20102", "super").contains(authority), expression.getValue(context, Boolean.class));
        }
    }

    @Test void retiresOriginalPermanentlyAndKeepsExistingInvitationRelations() {
        String next = (String) rotate("Original-_a1", "rotation-one").get("inviteCode");
        assertNotEquals("Original-_a1", next);
        assertNotNull(jdbc.queryForObject("SELECT retired_at FROM compute_member_invite_code WHERE code='Original-_a1'", LocalDateTime.class));
        assertEquals(1, jdbc.queryForObject("SELECT member_id FROM compute_member_invite_code WHERE code='Original-_a1'", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT inviter_id FROM compute_member WHERE id=2", Long.class));
        assertThrows(BusinessException.class, () -> scope.resolveInviter("Original-_a1"));
        assertEquals(1, scope.resolveInviter(next));
    }

    @Test void repeatsSameRequestWithoutAnotherRotationAndRejectsStaleOrConflictingPayload() {
        var first = rotate("Original-_a1", "rotation-one");
        assertEquals(first, rotate("Original-_a1", "rotation-one"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_invite_code", Integer.class));
        assertEquals("server.compute.requestConflict", assertThrows(BusinessException.class, () -> rotate("wrong-value", "rotation-one")).getMessage());
        assertEquals("server.compute.inviteCode.changed", assertThrows(BusinessException.class, () -> rotate("Original-_a1", "rotation-two")).getMessage());
        rotate(current(), "rotation-two");
        assertEquals("server.compute.inviteCode.changed", assertThrows(BusinessException.class, () -> rotate("Original-_a1", "rotation-one")).getMessage());
    }

    @Test void oldCodesCannotBeRegeneratedAndCollisionRetriesAreBounded() {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','inviteCodeMode','DIGITS'),('compute','inviteCodeLength','6')");
        jdbc.update("INSERT INTO compute_member_invite_code(code,member_id,created_at,retired_at) VALUES('000000',99,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var random = mock(SecureRandom.class); var counter = new AtomicInteger();
        when(random.nextInt(anyInt())).thenAnswer(call -> counter.incrementAndGet() <= 11 ? 0 : 1);
        var deterministic = new ComputeInviteCodeService(jdbc, scope, random);
        assertEquals("111111", tx.execute(status -> { scope.relations().lock(); return deterministic.nextCode(); }));
        when(random.nextInt(anyInt())).thenReturn(0);
        assertEquals("server.compute.inviteCode.exhausted", assertThrows(BusinessException.class,
                () -> tx.execute(status -> { scope.relations().lock(); return deterministic.nextCode(); })).getMessage());
    }

    @Test void failedTransactionPreservesCurrentCodeAndHistory() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            codes.rotate(1, request("Original-_a1", "rotation-rollback"));
            throw new IllegalStateException("模拟写入后失败");
        }));
        assertEquals("Original-_a1", current());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_invite_code", Integer.class));
    }

    @Test void rotationRechecksCurrentAdministratorScope() {
        admin("20102"); jdbc.update("INSERT INTO compute_admin_member_scope VALUES(1,2)");
        assertThrows(BusinessException.class, () -> rotate("Original-_a1", "rotation-denied"));
        assertEquals("Original-_a1", current());
    }

    @Test void concurrentRotationsKeepExactlyOneNewCode() throws Exception {
        var pool = Executors.newFixedThreadPool(6); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 6; i++) {
                String requestNo = "rotation-" + i;
                futures.add(pool.submit(() -> { admin("super"); start.await();
                    try { rotate("Original-_a1", requestNo); return true; }
                    catch (BusinessException expected) { assertEquals("server.compute.inviteCode.changed", expected.getMessage()); return false; }
                    finally { SecurityContextHolder.clearContext(); }
                }));
            }
            start.countDown(); int succeeded = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) succeeded++;
            assertEquals(1, succeeded);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_invite_code", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_invite_code WHERE retired_at IS NULL", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void publicAndAdminCreationUseSamePolicyAndHistory() {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','inviteCodeMode','DIGITS'),('compute','inviteCodeLength','9')");
        var accounts = new ComputeAccountService(jdbc, new PasswordUtils(), mock(ComputeBusinessService.class), scope);
        var publicResult = tx.execute(status -> accounts.register(new ComputeAuthRequests.Register("newpublic", null, null, "abcdef", "Original-_a1")));
        var adminResult = tx.execute(status -> accounts.createFromAdmin(new ComputeMemberCreateRequest("newadmin", null, null, "abcdef", "Original-_a1", true)));
        String publicCode = (String) ((Map<?, ?>) publicResult.get("member")).get("inviteCode");
        assertTrue(publicCode.matches("[0-9]{9}")); assertTrue(adminResult.inviteCode().matches("[0-9]{9}"));
        assertNotEquals(publicCode, adminResult.inviteCode());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_invite_code", Integer.class));
    }

    @Test void registrationUsingRetiredCodeFailsAfterWaitingForRotationTransaction() throws Exception {
        var pool = Executors.newSingleThreadExecutor(); var entered = new CountDownLatch(1);
        var accounts = new ComputeAccountService(jdbc, new PasswordUtils(), mock(ComputeBusinessService.class), scope);
        try {
            var future = tx.execute(status -> {
                scope.relations().lock();
                var pending = pool.submit(() -> { entered.countDown(); return assertThrows(BusinessException.class,
                        () -> tx.execute(inner -> accounts.register(new ComputeAuthRequests.Register("concurrentnew", null, null, "abcdef", "Original-_a1")))); });
                try { assertTrue(entered.await(5, TimeUnit.SECONDS)); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                codes.rotate(1, request("Original-_a1", "rotation-before-register"));
                return pending;
            });
            assertEquals("server.compute.invalidInviteCode", future.get(10, TimeUnit.SECONDS).getMessage());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_member WHERE username='concurrentnew'", Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
