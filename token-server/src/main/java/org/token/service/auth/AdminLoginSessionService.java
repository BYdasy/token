package org.token.service.auth;

import org.token.model.entity.Account;
import org.token.exception.BusinessException;
import org.token.tools.security.JWTUtil;
import org.token.service.compute.ComputeCredentials;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 后台登录会话持久保存，只有成功的前台操作续期，所有凭据必须显式登记。 */
@Service
public class AdminLoginSessionService {
    private final org.token.repository.system.AdminAuthRepository repository;
    private final LoginSessionPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminLoginSessionService(org.token.repository.system.AdminAuthRepository repository, LoginSessionPolicy policy, PlatformTransactionManager transactionManager) {
        this(repository, policy, Clock.systemUTC(), transactionManager);
    }
    AdminLoginSessionService(JdbcTemplate jdbc, LoginSessionPolicy policy, Clock clock) {
        this(new org.token.repository.system.AdminAuthRepository(jdbc), policy, clock, new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }
    private AdminLoginSessionService(org.token.repository.system.AdminAuthRepository repository, LoginSessionPolicy policy, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository; this.policy = policy; this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public String issue(Account account, Long totpVerifiedAt) {
        return issue(account, totpVerifiedAt, null);
    }

    /** 验证开始时间用于阻止安全变更之前发起、之后才返回的认证请求重新取得会话。 */
    public String issue(Account account, Long totpVerifiedAt, Long authenticationStartedAt) {
        return transactions.execute(status -> {
            requireCurrentAccount(account);
            requireCurrentAuthentication(account.getUid(), authenticationStartedAt);
            String token = JWTUtil.signSession(account.getUsername(), String.valueOf(account.getUid()), account.getPassword(),
                    java.util.UUID.randomUUID().toString(), totpVerifiedAt);
            LocalDateTime now = now();
            LocalDateTime created = nextCreatedAt(account.getUid(), now);
            replacePreviousSessions(account.getUid(), now);
            repository.insertSession(digest(token), account.getUid(), account.getPassword(), created, now, now.plus(policy.adminIdle()),null);
            return token;
        });
    }

    /** 绑定专用凭据保持十分钟绝对期限，同时参与退出与安全变更撤销。 */
    public String issueBinding(Account account) {
        return issueBinding(account, null);
    }

    public String issueBinding(Account account, Long authenticationStartedAt) {
        return transactions.execute(status -> {
            requireCurrentAccount(account);
            requireCurrentAuthentication(account.getUid(), authenticationStartedAt);
            String token = JWTUtil.sign(account.getUsername(), String.valueOf(account.getUid()), account.getPassword(),
                    10L * 60 * 1000, null, true);
            LocalDateTime now = now();
            LocalDateTime created = nextCreatedAt(account.getUid(), now);
            LocalDateTime expiry = LocalDateTime.ofInstant(JWTUtil.getExpiresAt(token), ZoneOffset.UTC);
            replacePreviousSessions(account.getUid(), now);
            repository.insertSession(digest(token), account.getUid(), account.getPassword(), created, now, expiry, expiry);
            return token;
        });
    }

    public boolean valid(String token, Account account) {
        return account != null && account.getUid() != null && validDigest(digest(token), account.getUid());
    }

    public boolean validDigest(String digest, Integer uid) {
        LocalDateTime now = now();
        java.util.List<Boolean> matches = repository.validCredentials(digest,uid,now,now.minus(policy.adminIdle()))
                .stream().map(pair -> sameCredential(pair.current(),pair.issued())).toList();
        return matches.size() == 1 && Boolean.TRUE.equals(matches.get(0));
    }

    /** 密码散列必须按原始字节精确比较，不能继承数据库的大小写不敏感排序规则。 */
    private static boolean sameCredential(String current, String issued) {
        return current != null && !current.isBlank() && issued != null && !issued.isBlank()
                && java.security.MessageDigest.isEqual(current.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                issued.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void recordActivity(String token, Integer uid) {
        if (JWTUtil.isTotpBindOnly(token)) return;
        LocalDateTime now = now();
        java.time.Duration idle = policy.adminIdle();
        repository.activity(digest(token),uid,now,now.plus(idle),now.minus(idle));
    }

    public void revoke(String token, Integer uid) {
        if (token == null || token.isBlank()) return;
        repository.revoke(digest(token),uid,now());
    }

    /** TOTP 绑定/重置/要求变化使既有凭据及变更前发起的认证请求失效。 */
    public void revokeAll(Integer uid) {
        transactions.executeWithoutResult(status -> {
            // 与签发使用同一账号行锁，避免撤销扫描完成后迟到插入仍有效的旧会话。
            if (!repository.lockUid(uid)) return;
            LocalDateTime now = now();
            int changed = repository.advanceBoundary(uid,now);
            if (changed == 0) {
                try { repository.insertBoundary(uid,now); }
                catch (DuplicateKeyException ignored) { /* 已有更晚或相同边界，无需倒退。 */ }
            }
            replacePreviousSessions(uid, now);
        });
    }

    private void replacePreviousSessions(Integer uid, LocalDateTime now) {
        repository.revokeAll(uid,now);
    }

    private void requireCurrentAccount(Account account) {
        if (!lockCurrentAccount(account)) throw new BusinessException("server.login.error");
    }

    private void requireCurrentAuthentication(Integer uid, Long authenticationStartedAt) {
        if (authenticationStartedAt == null) return;
        LocalDateTime started = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(authenticationStartedAt), ZoneOffset.UTC);
        java.util.List<LocalDateTime> boundaries = repository.boundaries(uid);
        if (boundaries.stream().anyMatch(boundary -> boundary != null && !started.isAfter(boundary)))
            throw new BusinessException("server.login.error");
    }

    private boolean lockCurrentAccount(Account account) {
        if (account == null || account.getUid() == null) return false;
        java.util.List<Boolean> rows = repository.lockAccount(account.getUid()).stream()
                .map(current -> Integer.valueOf(1).equals(current.getState()) && sameCredential(current.getPassword(),account.getPassword())).toList();
        return rows.size() == 1 && Boolean.TRUE.equals(rows.get(0));
    }

    /** 数据库精度为微秒，同一时钟刻度的新登录也必须排在旧会话和撤销边界之后。 */
    private LocalDateTime nextCreatedAt(Integer uid, LocalDateTime now) {
        LocalDateTime previous = repository.lastCreated(uid);
        java.util.List<LocalDateTime> boundaries = repository.boundaries(uid);
        LocalDateTime result = previous != null && !now.isAfter(previous) ? previous.plusNanos(1_000) : now;
        for (LocalDateTime boundary : boundaries) if (boundary != null && !result.isAfter(boundary)) result = boundary.plusNanos(1_000);
        return result;
    }

    public static String digest(String token) {
        String value = token == null ? "" : token.trim();
        return ComputeCredentials.digest(value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value);
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
}
