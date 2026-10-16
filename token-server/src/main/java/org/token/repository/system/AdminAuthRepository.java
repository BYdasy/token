package org.token.repository.system;

import org.token.repository.dao.AdminAuthMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.model.entity.Account;
import org.token.model.entity.AdminLoginAttemptState;
import org.token.model.entity.AdminSessionCredential;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

/** 会话与登录失败的持久化边界；锁顺序和事务仍由认证服务控制。 */
@Repository
public class AdminAuthRepository {
    private final AdminAuthMapper mapper;
    @Autowired public AdminAuthRepository(AdminAuthMapper mapper) { this.mapper=mapper; }
    public AdminAuthRepository(JdbcTemplate jdbc) { this(AnnotationMapperFactory.mapper(jdbc,AdminAuthMapper.class)); }
    public List<Account> lockAccount(Integer uid) { return mapper.lockAccount(uid); }
    public boolean lockUid(Integer uid) { return !mapper.lockUid(uid).isEmpty(); }
    public List<AdminLoginAttemptState> attempts(Integer uid) { return mapper.attempts(uid); }
    public void saveAttempt(int uid,int count,LocalDateTime until,LocalDateTime now) {
        mapper.saveAttempt(uid,count,until,now);
    }
    public void insertSession(String digest,Integer uid,String credential,LocalDateTime created,LocalDateTime active,LocalDateTime expiry,LocalDateTime absolute) { mapper.insertSession(digest,uid,credential,created,active,expiry,absolute); }
    public List<AdminSessionCredential> validCredentials(String digest,Integer uid,LocalDateTime now,LocalDateTime cutoff) { return mapper.validCredentials(digest,uid,now,cutoff); }
    public void activity(String digest,Integer uid,LocalDateTime now,LocalDateTime expiry,LocalDateTime cutoff) { mapper.activity(digest,uid,now,expiry,cutoff); }
    public void revoke(String digest,Integer uid,LocalDateTime now) { mapper.revoke(digest,uid,now); }
    public void revokeAll(Integer uid,LocalDateTime now) { mapper.revokeAll(uid,now); }
    public int advanceBoundary(Integer uid,LocalDateTime now) { return mapper.advanceBoundary(uid,now); }
    public void insertBoundary(Integer uid,LocalDateTime now) { mapper.insertBoundary(uid,now); }
    public List<LocalDateTime> boundaries(Integer uid) { return mapper.boundaries(uid); }
    public LocalDateTime lastCreated(Integer uid) { return mapper.lastCreated(uid); }
}
