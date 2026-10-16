package org.token.repository.dao.compute;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ComputeInviteCodeMapper {
    @Select("SELECT setting_key,setting_value FROM system_setting WHERE setting_key IN ('inviteCodeMode','inviteCodeLength')")
    List<Map<String,Object>> policy();
    @Select("SELECT COUNT(*) FROM compute_member_invite_code WHERE code=#{code}")
    long historyCount(@Param("code") String code);
    @Select("SELECT COUNT(*) FROM compute_member WHERE invite_code=#{code}")
    long memberCount(@Param("code") String code);
    @Insert("INSERT INTO compute_member_invite_code(code,member_id,created_at) VALUES(#{code},#{memberId},#{now})")
    int recordCreated(@Param("memberId") long memberId,@Param("code") String code,@Param("now") LocalDateTime now);
    @Select("SELECT invite_code FROM compute_member WHERE id=#{memberId} FOR UPDATE")
    String lockCurrent(@Param("memberId") long memberId);
    @Select("SELECT code,previous_code FROM compute_member_invite_code WHERE member_id=#{memberId} AND request_no=#{requestNo}")
    List<Map<String,Object>> previous(@Param("memberId") long memberId,@Param("requestNo") String requestNo);
    @Select("SELECT member_id,retired_at FROM compute_member_invite_code WHERE code=#{code}")
    List<Map<String,Object>> owner(@Param("code") String code);
    @Insert("INSERT INTO compute_member_invite_code(code,member_id,previous_code,request_no,created_at) VALUES(#{next},#{memberId},#{current},#{requestNo},#{now})")
    int recordRotation(@Param("memberId") long memberId,@Param("current") String current,@Param("next") String next,@Param("requestNo") String requestNo,@Param("now") LocalDateTime now);
    @Update("UPDATE compute_member_invite_code SET retired_at=#{now} WHERE code=#{code} AND member_id=#{memberId} AND retired_at IS NULL")
    int retire(@Param("memberId") long memberId,@Param("code") String code,@Param("now") LocalDateTime now);
    @Update("UPDATE compute_member SET invite_code=#{code},updated_at=#{now} WHERE id=#{memberId}")
    int replace(@Param("memberId") long memberId,@Param("code") String code,@Param("now") LocalDateTime now);
}
