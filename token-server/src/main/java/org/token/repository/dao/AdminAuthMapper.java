package org.token.repository.dao;

import org.apache.ibatis.annotations.*;
import org.token.model.entity.Account;
import org.token.model.entity.AdminLoginAttemptState;
import org.token.model.entity.AdminSessionCredential;
import java.time.LocalDateTime;
import java.util.List;

/** 管理员认证状态专用 SQL，所有行锁和条件写入均加入调用方事务。 */
@Mapper
public interface AdminAuthMapper {
    @Select("SELECT password,state FROM ad_master WHERE uid=#{uid} FOR UPDATE")
    List<Account> lockAccount(Integer uid);

    @Select("SELECT uid FROM ad_master WHERE uid=#{uid} FOR UPDATE")
    List<Integer> lockUid(Integer uid);

    @Select("SELECT failed_count,locked_until FROM admin_security WHERE admin_uid=#{uid}")
    @ConstructorArgs({@Arg(column="failed_count",javaType=int.class),@Arg(column="locked_until",javaType=LocalDateTime.class)})
    List<AdminLoginAttemptState> attempts(Integer uid);

    @Insert("INSERT INTO admin_security(admin_uid,failed_count,locked_until,login_attempt_updated_at,updated_at) VALUES(#{uid},#{count},#{until},#{now},#{now}) ON DUPLICATE KEY UPDATE failed_count=VALUES(failed_count),locked_until=VALUES(locked_until),login_attempt_updated_at=VALUES(login_attempt_updated_at),updated_at=VALUES(updated_at)")
    int saveAttempt(@Param("uid") int uid,@Param("count") int count,@Param("until") LocalDateTime until,@Param("now") LocalDateTime now);

    @Insert("INSERT INTO admin_login_session(token_hash,admin_uid,credential_hash,created_at,last_active_at,expires_at,absolute_expires_at) VALUES(#{digest},#{uid},#{credential},#{created},#{active},#{expiry},#{absolute})")
    int insertSession(@Param("digest") String digest,@Param("uid") Integer uid,@Param("credential") String credential,@Param("created") LocalDateTime created,@Param("active") LocalDateTime active,@Param("expiry") LocalDateTime expiry,@Param("absolute") LocalDateTime absolute);

    @Select("""
        SELECT a.password,s.credential_hash FROM admin_login_session s JOIN ad_master a ON a.uid=s.admin_uid
        LEFT JOIN admin_security security ON security.admin_uid=s.admin_uid
        WHERE s.token_hash=#{digest} AND s.admin_uid=#{uid} AND s.revoked_at IS NULL AND s.expires_at>#{now}
          AND s.last_active_at>#{cutoff} AND a.state=1
          AND (s.absolute_expires_at IS NULL OR s.absolute_expires_at>#{now})
          AND (security.invalid_before IS NULL OR s.created_at>security.invalid_before)
          AND NOT EXISTS (SELECT 1 FROM admin_login_session newer WHERE newer.admin_uid=s.admin_uid
            AND (newer.created_at>s.created_at OR (newer.created_at=s.created_at AND newer.token_hash>s.token_hash)))
        """)
    @ConstructorArgs({@Arg(column="password",javaType=String.class),@Arg(column="credential_hash",javaType=String.class)})
    List<AdminSessionCredential> validCredentials(@Param("digest") String digest,@Param("uid") Integer uid,@Param("now") LocalDateTime now,@Param("cutoff") LocalDateTime cutoff);

    @Update("""
        UPDATE admin_login_session SET last_active_at=#{now},expires_at=CASE WHEN absolute_expires_at IS NOT NULL AND absolute_expires_at<#{expiry} THEN absolute_expires_at ELSE #{expiry} END
        WHERE token_hash=#{digest} AND admin_uid=#{uid} AND revoked_at IS NULL AND expires_at>#{now} AND last_active_at>#{cutoff}
        """)
    int activity(@Param("digest") String digest,@Param("uid") Integer uid,@Param("now") LocalDateTime now,@Param("expiry") LocalDateTime expiry,@Param("cutoff") LocalDateTime cutoff);

    @Update("UPDATE admin_login_session SET revoked_at=#{now} WHERE token_hash=#{digest} AND admin_uid=#{uid} AND revoked_at IS NULL")
    int revoke(@Param("digest") String digest,@Param("uid") Integer uid,@Param("now") LocalDateTime now);
    @Update("UPDATE admin_login_session SET revoked_at=#{now} WHERE admin_uid=#{uid} AND revoked_at IS NULL")
    int revokeAll(@Param("uid") Integer uid,@Param("now") LocalDateTime now);
    @Update("UPDATE admin_security SET invalid_before=#{now},updated_at=#{now} WHERE admin_uid=#{uid} AND (invalid_before IS NULL OR invalid_before<#{now})")
    int advanceBoundary(@Param("uid") Integer uid,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO admin_security(admin_uid,invalid_before,updated_at) VALUES(#{uid},#{now},#{now})")
    int insertBoundary(@Param("uid") Integer uid,@Param("now") LocalDateTime now);
    @Select("SELECT invalid_before FROM admin_security WHERE admin_uid=#{uid}")
    List<LocalDateTime> boundaries(Integer uid);
    @Select("SELECT MAX(created_at) FROM admin_login_session WHERE admin_uid=#{uid}")
    LocalDateTime lastCreated(Integer uid);
}
