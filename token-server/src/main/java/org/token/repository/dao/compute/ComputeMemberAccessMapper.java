package org.token.repository.dao.compute;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ComputeMemberAccessMapper {
    @Select("SELECT password_hash FROM compute_member WHERE id=#{id} AND enabled=1 FOR UPDATE") List<String> lockMemberHashes(@Param("id") long id);
    @Select("SELECT password FROM ad_master WHERE uid=#{uid} AND state=1") String adminHash(@Param("uid") int uid);
    @Delete("DELETE FROM compute_member_login_ticket WHERE expires_at<=UTC_TIMESTAMP(6)") int deleteExpiredTickets();
    @Insert("INSERT INTO compute_member_login_ticket(ticket_hash,member_id,admin_uid,admin_credential_hash,member_credential_hash,expires_at,created_at) VALUES(#{digest},#{id},#{uid},#{adminHash},#{memberHash},#{expiry},UTC_TIMESTAMP(6))")
    int insertTicket(@Param("digest") String digest,@Param("id") long id,@Param("uid") int uid,@Param("adminHash") String adminHash,@Param("memberHash") String memberHash,@Param("expiry") LocalDateTime expiry);
    @Select("SELECT * FROM compute_member_login_ticket WHERE ticket_hash=#{digest} AND expires_at>UTC_TIMESTAMP(6)") List<Map<String,Object>> tickets(@Param("digest") String digest);
    @Select("SELECT a.uid,a.username,a.password,r.role,r.isSelf is_super FROM ad_master a JOIN user_role r ON r.id=a.role WHERE a.uid=#{uid} AND a.state=1")
    List<Map<String,Object>> admins(@Param("uid") Object uid);
    @Delete("DELETE FROM compute_member_login_ticket WHERE ticket_hash=#{digest} AND expires_at>UTC_TIMESTAMP(6)") int consumeTicket(@Param("digest") String digest);
    @Select("SELECT id FROM compute_member WHERE id=#{id} FOR UPDATE") List<Long> lockMember(@Param("id") long id);
    @Delete("DELETE FROM compute_member_login_ticket WHERE member_id=#{id}") int deleteTickets(@Param("id") long id);
    @Delete("DELETE FROM compute_member_session WHERE member_id=#{id}") int deleteSessions(@Param("id") long id);
}
