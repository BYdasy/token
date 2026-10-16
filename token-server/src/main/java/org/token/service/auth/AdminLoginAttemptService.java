package org.token.service.auth;

import org.token.model.entity.Account;
import org.token.util.PasswordUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** 管理员安全记录保存失败次数及临时锁定；账号行锁串行化每次计数。 */
@Service
public class AdminLoginAttemptService {
    public static final int MAX_FAILURES = 5;
    public static final int LOCK_MINUTES = 15;
    public enum Check { ACCEPTED, INVALID, LOCKED }
    private final org.token.repository.system.AdminAuthRepository repository;
    private final PasswordUtils passwords;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public AdminLoginAttemptService(org.token.repository.system.AdminAuthRepository repository, PasswordUtils passwords, PlatformTransactionManager manager) {
        this(repository, passwords, manager, Clock.systemUTC());
    }

    AdminLoginAttemptService(JdbcTemplate jdbc, PasswordUtils passwords, PlatformTransactionManager manager, Clock clock) {
        this(new org.token.repository.system.AdminAuthRepository(jdbc),passwords,manager,clock);
    }

    private AdminLoginAttemptService(org.token.repository.system.AdminAuthRepository repository, PasswordUtils passwords, PlatformTransactionManager manager, Clock clock) {
        this.repository=repository;this.passwords=passwords;this.transactions=new TransactionTemplate(manager);this.clock=clock;
    }

    public Check verifyPassword(Account account, String password) {
        return transactions.execute(status -> {
            if (!lockCurrentAccount(account)) return Check.INVALID;
            State state = state(account.getUid());
            if (state.lockedUntil()!=null && state.lockedUntil().isAfter(now())) return Check.LOCKED;
            if (!passwords.matchesPassword(password, account.getPassword())) return fail(account.getUid(), state);
            return Check.ACCEPTED;
        });
    }

    /** 二次验证输错也计入同一窗口；仅请求验证码或绑定引导不算失败。 */
    public Check secondFactorFailed(Account account) {
        return transactions.execute(status -> {
            if (!lockCurrentAccount(account)) return Check.INVALID;
            State state=state(account.getUid());
            if (state.lockedUntil()!=null && state.lockedUntil().isAfter(now())) return Check.LOCKED;
            return fail(account.getUid(), state);
        });
    }

    public void succeeded(Account account) {
        transactions.executeWithoutResult(status -> {
            if (lockCurrentAccount(account)) save(account.getUid(), 0, null);
        });
    }

    private boolean lockCurrentAccount(Account account) {
        if(account==null || account.getUid()==null)return false;
        var rows=repository.lockAccount(account.getUid());
        if(rows.isEmpty())return false;
        var row=rows.get(0);
        return Integer.valueOf(1).equals(row.getState()) && Objects.equals(account.getPassword(),row.getPassword());
    }

    private State state(int uid) {
        var rows=repository.attempts(uid);
        if(rows.isEmpty())return new State(0,null);
        State state=new State(rows.get(0).count(),rows.get(0).lockedUntil());
        if(state.lockedUntil()!=null && !state.lockedUntil().isAfter(now())) {
            save(uid,0,null);return new State(0,null);
        }
        return state;
    }

    private Check fail(int uid,State state) {
        int count=Math.min(MAX_FAILURES,state.count()+1);
        LocalDateTime until=count>=MAX_FAILURES?now().plusMinutes(LOCK_MINUTES):null;
        save(uid,count,until);
        return until==null?Check.INVALID:Check.LOCKED;
    }

    private void save(int uid,int count,LocalDateTime until) {
        repository.saveAttempt(uid,count,until,now());
    }

    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private record State(int count,LocalDateTime lockedUntil) { }
}
