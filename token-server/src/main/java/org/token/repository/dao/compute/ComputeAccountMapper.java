package org.token.repository.dao.compute;

import org.apache.ibatis.annotations.*;
import org.token.repository.compute.ComputeAccountRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 会员账户与会话SQL；密码验证、权限与事务均由业务服务控制。 */
@Mapper
public interface ComputeAccountMapper {
    @Select("SELECT setting_key,setting_value FROM system_setting WHERE group_name='compute'") List<Map<String,Object>> settings();
    @Select("SELECT id,username,email,phone,level,invite_code,user_time_zone,(NULLIF(TRIM(pay_password_hash),'') IS NOT NULL) has_pay_password,created_at FROM compute_member WHERE id=#{id} AND enabled=1")
    List<Map<String,Object>> member(@Param("id") long id);
    @Update("UPDATE compute_member SET is_test=1 WHERE id=#{id}") int markTest(@Param("id") long id);
    @Select("SELECT invite_code,created_at FROM compute_member WHERE id=#{id}") Map<String,Object> creationInfo(@Param("id") long id);
    @Select("SELECT id FROM compute_member WHERE invite_code=#{code} AND enabled=1") List<Long> memberInviter(@Param("code") String code);
    @Insert("""
            <script>INSERT INTO compute_member(
              <if test="row.id != null">id,</if>
              username,email,phone,password_hash,level,enabled,invite_code,inviter_id,group_id,created_at,updated_at)
            VALUES(<if test="row.id != null">#{row.id},</if>
              #{row.username},#{row.email},#{row.phone},#{row.passwordHash},#{row.level},#{row.enabled},#{row.inviteCode},
              #{row.inviterId},#{row.groupId},#{row.now},#{row.now})</script>
            """)
    @Options(useGeneratedKeys=true,keyProperty="row.id",keyColumn="id")
    int insertMember(@Param("row") Map<String,Object> row);
    @SelectProvider(type=ComputeAccountRepository.Queries.class,method="login")
    List<Map<String,Object>> login(@Param("method") String method,@Param("account") String account);
    @Select("""
            SELECT m.id,m.username FROM compute_member_session s JOIN compute_member m ON m.id=s.member_id
            WHERE s.token_hash=#{digest} AND s.expires_at>UTC_TIMESTAMP(6) AND m.enabled=1 AND s.credential_hash=m.password_hash
              AND (s.last_seen_at IS NULL OR s.last_seen_at>#{cutoff})
              AND NOT EXISTS(
                SELECT 1 FROM compute_member_session other_session
                WHERE other_session.member_id=s.member_id AND other_session.token_hash<>s.token_hash
                  AND other_session.credential_hash=m.password_hash AND other_session.expires_at>UTC_TIMESTAMP(6)
                  AND (other_session.last_seen_at IS NULL OR other_session.last_seen_at>#{cutoff})
              )
            """)
    List<Map<String,Object>> authenticate(@Param("digest") String digest,@Param("cutoff") LocalDateTime cutoff);
    @Select("SELECT password_hash FROM compute_member WHERE id=#{id} AND enabled=1 FOR UPDATE") List<String> lockActiveHashes(@Param("id") long id);
    @Select("SELECT password_hash FROM compute_member WHERE id=#{id} FOR UPDATE") String lockPassword(@Param("id") long id);
    @Delete("DELETE FROM compute_member_session WHERE member_id=#{id}") int deleteSessions(@Param("id") long id);
    @Insert("INSERT INTO compute_member_session(token_hash,member_id,credential_hash,expires_at,last_seen_at) VALUES(#{digest},#{id},#{credentialHash},#{expires},#{now})")
    int insertSession(@Param("digest") String digest,@Param("id") long id,@Param("credentialHash") String credentialHash,@Param("expires") LocalDateTime expires,@Param("now") LocalDateTime now);
    @Update("""
            UPDATE compute_member_session SET last_seen_at=#{now},expires_at=#{expires}
            WHERE token_hash=#{digest} AND expires_at>#{now} AND (last_seen_at IS NULL OR last_seen_at>#{cutoff})
              AND EXISTS(SELECT 1 FROM compute_member m WHERE m.id=compute_member_session.member_id
                AND m.enabled=1 AND m.password_hash=compute_member_session.credential_hash)
            """)
    int sessionActivity(@Param("digest") String digest,@Param("now") LocalDateTime now,@Param("expires") LocalDateTime expires,@Param("cutoff") LocalDateTime cutoff);
    @Update("UPDATE compute_member SET last_login_at=UTC_TIMESTAMP(6),last_login_ip=#{ip},last_login_country=#{country} WHERE id=#{id}")
    int recordAccess(@Param("id") long id,@Param("ip") String ip,@Param("country") String country);
    @Update("UPDATE compute_member SET registration_ip=#{ip},registration_country=#{country} WHERE id=#{id}")
    int recordRegistration(@Param("id") long id,@Param("ip") String ip,@Param("country") String country);
    @Update("UPDATE compute_member SET password_hash=#{hash},updated_at=UTC_TIMESTAMP(6) WHERE id=#{id}") int password(@Param("id") long id,@Param("hash") String hash);
    @Select("SELECT password_hash,pay_password_hash FROM compute_member WHERE id=#{id} AND enabled=1 FOR UPDATE") List<Map<String,Object>> lockPayCredentials(@Param("id") long id);
    @Update("UPDATE compute_member SET pay_password_hash=#{hash},updated_at=UTC_TIMESTAMP(6) WHERE id=#{id}") int payPassword(@Param("id") long id,@Param("hash") String hash);
    @Select("SELECT id FROM compute_member WHERE id=#{id} FOR UPDATE") List<Long> lockMember(@Param("id") long id);
    @Update("UPDATE compute_member SET username=#{username},email=#{email},phone=#{phone},updated_at=UTC_TIMESTAMP(6) WHERE id=#{id}")
    int profile(@Param("id") long id,@Param("username") Object username,@Param("email") Object email,@Param("phone") Object phone);
    @Delete("DELETE FROM compute_member_session WHERE token_hash=#{digest}") int logout(@Param("digest") String digest);
    @SelectProvider(type=ComputeAccountRepository.Queries.class,method="recoveryMember")
    List<Long> recoveryMember(@Param("method") String method,@Param("account") String account);
    @Select("SELECT COUNT(*) FROM compute_recovery_request WHERE member_id=#{id} AND status='PENDING'") int pendingRecoveryCount(@Param("id") long id);
    @Insert("INSERT INTO compute_recovery_request(member_id,method,account,status,created_at) VALUES(#{id},#{method},#{account},'PENDING',UTC_TIMESTAMP(6))")
    int requestRecovery(@Param("id") long id,@Param("method") String method,@Param("account") String account);
    @SelectProvider(type=ComputeAccountRepository.Queries.class,method="recoveryCount") long recoveryCount(Map<String,Object> query);
    @SelectProvider(type=ComputeAccountRepository.Queries.class,method="recoveries") List<Map<String,Object>> recoveries(Map<String,Object> query);
    @Select("SELECT member_id FROM compute_recovery_request WHERE id=#{id} AND status='PENDING'") List<Long> pendingRecoveryMembers(@Param("id") long id);
    @Select("SELECT id FROM compute_recovery_request WHERE id=#{id} AND status='PENDING' FOR UPDATE") List<Long> lockPendingRecovery(@Param("id") long id);
    @Update("UPDATE compute_recovery_request SET status='EXPIRED' WHERE member_id=#{id} AND status='ISSUED'") int expireRecoveries(@Param("id") long id);
    @Update("UPDATE compute_recovery_request SET status='ISSUED',token_hash=#{digest},expires_at=#{expires},reviewer_id=#{adminId} WHERE id=#{id}")
    int issueRecovery(@Param("id") long id,@Param("digest") String digest,@Param("expires") LocalDateTime expires,@Param("adminId") int adminId);
    @Select("SELECT member_id FROM compute_recovery_request WHERE token_hash=#{digest} AND status='ISSUED' AND expires_at>UTC_TIMESTAMP(6)")
    List<Long> validRecoveryMembers(@Param("digest") String digest);
    @Select("SELECT id FROM compute_recovery_request WHERE token_hash=#{digest} AND status='ISSUED' AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE")
    List<Long> lockValidRecovery(@Param("digest") String digest);
    @Update("UPDATE compute_recovery_request SET status='USED',used_at=UTC_TIMESTAMP(6) WHERE token_hash=#{digest}") int useRecovery(@Param("digest") String digest);
}
