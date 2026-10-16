package org.token.repository.dao.compute;

import org.apache.ibatis.annotations.*;
import org.token.repository.compute.ComputeAgentScopeRepository;
import java.util.List;

@Mapper
public interface ComputeAgentScopeMapper {
    @Select("SELECT root_member_id FROM compute_admin_member_scope WHERE admin_uid=#{uid} ORDER BY root_member_id")
    List<Long> roots(@Param("uid") int uid);
    @Select("""
            <script>SELECT id FROM compute_member WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            AND enabled=1 ORDER BY id</script>
            """)
    List<Long> activeRoots(@Param("ids") List<Long> ids);
    @Select("SELECT COUNT(*) FROM ad_master WHERE uid=#{uid} AND scope_configured_at IS NOT NULL") long configured(@Param("uid") int uid);
    @SelectProvider(type=ComputeAgentScopeRepository.Queries.class,method="memberCount")
    long memberCount(@Param("id") long id,@Param("global") boolean global,@Param("enabled") boolean enabled,@Param("roots") List<Long> roots);
    @Select("SELECT COUNT(*) FROM compute_member WHERE id=#{id} AND enabled=1") long activeMember(@Param("id") long id);
    @Select("SELECT setting_value FROM system_setting WHERE setting_key='invitationEnabled'") List<String> invitationEnabled();
    @Select("SELECT id FROM compute_member WHERE invite_code=#{code} AND enabled=1 AND invitation_enabled=1") List<Long> inviter(@Param("code") String code);
}
