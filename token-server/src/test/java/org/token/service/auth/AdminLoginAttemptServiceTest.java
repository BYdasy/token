package org.token.service.auth;

import org.token.model.entity.Account;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.token.service.auth.AdminLoginAttemptService.Check.*;

/** H2固定时钟验证临时锁定，不读取真实账号也不调用外部登录。 */
class AdminLoginAttemptServiceTest {
    JdbcTemplate jdbc;
    PasswordUtils passwords;
    AdminLoginAttemptService attempts;
    Account account;
    MutableClock clock;

    @BeforeEach void setup() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:login_attempt_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE ad_master(uid INT PRIMARY KEY,password VARCHAR(100),state INT)");
        jdbc.execute("CREATE TABLE admin_security(admin_uid INT PRIMARY KEY,failed_count INT NOT NULL DEFAULT 0,locked_until TIMESTAMP,login_attempt_updated_at TIMESTAMP,updated_at TIMESTAMP NOT NULL,totp_secret_ciphertext VARCHAR(500),totp_enabled INT DEFAULT 0,totp_required INT DEFAULT 0,totp_last_used_counter BIGINT,totp_confirmed_at TIMESTAMP,invalid_before TIMESTAMP)");
        jdbc.update("INSERT INTO ad_master VALUES(1,'current-hash',1),(2,'other-hash',1)");
        jdbc.update("INSERT INTO admin_security(admin_uid,failed_count,updated_at) VALUES(1,0,CURRENT_TIMESTAMP),(2,0,CURRENT_TIMESTAMP)");
        account=new Account();account.setUid(1);account.setPassword("current-hash");account.setState(1);
        passwords=mock(PasswordUtils.class);when(passwords.matchesPassword("correct","current-hash")).thenReturn(true);
        clock=new MutableClock();attempts=new AdminLoginAttemptService(jdbc,passwords,new DataSourceTransactionManager(source),clock);
    }

    @Test void successfulCompleteAuthenticationResetsTheSecurityCounter() {
        assertEquals(ACCEPTED,attempts.verifyPassword(account,"correct"));
        attempts.succeeded(account);
        assertEquals(0,jdbc.queryForObject("SELECT failed_count FROM admin_security WHERE admin_uid=1",Integer.class));
    }

    @Test void failedLoginAndSuccessfulResetNeverOverwriteTotpOrRevocationBoundary() {
        jdbc.update("UPDATE admin_security SET totp_secret_ciphertext='ciphertext',totp_enabled=1,totp_required=1,totp_last_used_counter=5678,invalid_before=TIMESTAMP '2026-09-22 00:00:00' WHERE admin_uid=1");
        assertEquals(INVALID,attempts.verifyPassword(account,"wrong"));
        attempts.succeeded(account);
        var row=jdbc.queryForMap("SELECT * FROM admin_security WHERE admin_uid=1");
        assertEquals("ciphertext",row.get("totp_secret_ciphertext"));
        assertEquals(1,row.get("totp_enabled"));
        assertEquals(1,row.get("totp_required"));
        assertEquals(5678L,((Number)row.get("totp_last_used_counter")).longValue());
        assertNotNull(row.get("invalid_before"));
        assertNotNull(row.get("login_attempt_updated_at"));
    }

    @Test void totpProjectionAndCounterUpdatesRetainLoginLockAndRevocationBoundary() {
        jdbc.update("UPDATE admin_security SET totp_secret_ciphertext='ciphertext',totp_enabled=1,totp_required=1,totp_last_used_counter=5678,failed_count=4,locked_until=TIMESTAMP '2026-09-24 00:00:00',invalid_before=TIMESTAMP '2026-09-22 00:00:00' WHERE admin_uid=1");
        var repository=new org.token.repository.system.AccountTotpRepository(
                org.token.repository.support.AnnotationMapperFactory.mapper(jdbc,org.token.repository.dao.AccountTotpMapper.class));
        var record=repository.getById(1);
        assertEquals(1,record.getUid());assertEquals("ciphertext",record.getSecretCiphertext());
        assertEquals(1,record.getEnabled());assertEquals(5678L,record.getLastUsedCounter());
        assertFalse(repository.unusedCounter(1,5678));assertTrue(repository.unusedCounter(1,5679));
        repository.resetSecret(1,"");
        var row=jdbc.queryForMap("SELECT * FROM admin_security WHERE admin_uid=1");
        assertEquals(4,row.get("failed_count"));assertNotNull(row.get("locked_until"));assertNotNull(row.get("invalid_before"));
        assertEquals(0,row.get("totp_enabled"));assertNull(row.get("totp_last_used_counter"));
    }

    @Test void fifthFailureLocksForFifteenMinutesAndLockedRequestsDoNotExtendIt() {
        for(int i=0;i<4;i++)assertEquals(INVALID,attempts.verifyPassword(account,"wrong"));
        assertEquals(LOCKED,attempts.verifyPassword(account,"wrong"));
        Object locked=jdbc.queryForObject("SELECT locked_until FROM admin_security WHERE admin_uid=1",Object.class);
        clearInvocations(passwords);
        clock.advance(Duration.ofMinutes(14));
        assertEquals(LOCKED,attempts.verifyPassword(account,"correct"));
        verifyNoInteractions(passwords);
        assertEquals(locked,jdbc.queryForObject("SELECT locked_until FROM admin_security WHERE admin_uid=1",Object.class));
        clock.advance(Duration.ofMinutes(1));
        assertEquals(ACCEPTED,attempts.verifyPassword(account,"correct"));
        assertEquals(0,jdbc.queryForObject("SELECT failed_count FROM admin_security WHERE admin_uid=1",Integer.class));
    }

    @Test void secondFactorFailuresCountWithoutCorrectPasswordClearingTheCounter() {
        for(int i=0;i<4;i++)assertEquals(INVALID,attempts.secondFactorFailed(account));
        assertEquals(ACCEPTED,attempts.verifyPassword(account,"correct"));
        assertEquals(4,jdbc.queryForObject("SELECT failed_count FROM admin_security WHERE admin_uid=1",Integer.class));
        assertEquals(LOCKED,attempts.secondFactorFailed(account));
    }

    @Test void expiredLockStartsNewWindowRatherThanImmediatelyRelocking() {
        for(int i=0;i<5;i++)attempts.verifyPassword(account,"wrong");
        clock.advance(Duration.ofMinutes(16));
        assertEquals(INVALID,attempts.verifyPassword(account,"wrong"));
        assertEquals(1,jdbc.queryForObject("SELECT failed_count FROM admin_security WHERE admin_uid=1",Integer.class));
        assertNull(jdbc.queryForObject("SELECT locked_until FROM admin_security WHERE admin_uid=1",Object.class));
    }

    @Test
    @ResourceLock("java.util.TimeZone.default")
    void utcDatabaseLockExpiresAfterFifteenMinutesWithShanghaiJvm() {
        TimeZone previous = TimeZone.getDefault();
        var source = (JdbcDataSource) jdbc.getDataSource();
        String previousUrl = source.getURL();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            // 模拟生产环境的UTC数据库连接及非UTC进程，不使用真实MySQL。
            source.setURL(previousUrl + ";INIT=SET TIME ZONE 'UTC'");
            for (int i = 0; i < 5; i++) attempts.verifyPassword(account, "wrong");
            clock.advance(Duration.ofMinutes(14));
            assertEquals(LOCKED, attempts.verifyPassword(account, "correct"));
            clock.advance(Duration.ofMinutes(1));
            assertEquals(ACCEPTED, attempts.verifyPassword(account, "correct"));
        } finally {
            source.setURL(previousUrl);
            TimeZone.setDefault(previous);
        }
    }

    @Test void disabledAndChangedCredentialAreRejectedWithoutVerifyingStalePassword() {
        jdbc.update("UPDATE ad_master SET state=0 WHERE uid=1");
        assertEquals(INVALID,attempts.verifyPassword(account,"correct"));
        jdbc.update("UPDATE ad_master SET state=1,password='new-hash' WHERE uid=1");
        assertEquals(INVALID,attempts.verifyPassword(account,"correct"));
        verifyNoInteractions(passwords);
    }

    @Test void concurrentFailuresAreSerializedPerAccountAndDoNotAffectOthers() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            var futures=new ArrayList<Future<AdminLoginAttemptService.Check>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->attempts.verifyPassword(account,"wrong")));
            for(var future:futures)assertNotEquals(ACCEPTED,future.get(15,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertEquals(5,jdbc.queryForObject("SELECT failed_count FROM admin_security WHERE admin_uid=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT failed_count FROM admin_security WHERE admin_uid=2",Integer.class));
        verify(passwords,times(5)).matchesPassword("wrong","current-hash");
    }

    static class MutableClock extends Clock {
        private Instant instant=Instant.parse("2026-09-23T00:00:00Z");
        void advance(Duration duration){instant=instant.plus(duration);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return instant;}
    }
}
