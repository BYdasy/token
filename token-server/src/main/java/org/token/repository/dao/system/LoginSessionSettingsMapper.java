package org.token.repository.dao.system;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LoginSessionSettingsMapper {
    @Update("UPDATE admin_login_session SET expires_at=CASE WHEN absolute_expires_at IS NULL THEN TIMESTAMPADD(HOUR,#{hours},last_active_at) ELSE LEAST(absolute_expires_at,TIMESTAMPADD(HOUR,#{hours},last_active_at)) END WHERE revoked_at IS NULL AND expires_at>#{now} AND last_active_at>#{oldCutoff}")
    int updateActiveAdminExpiry(@Param("hours") int hours, @Param("now") LocalDateTime now,
                               @Param("oldCutoff") LocalDateTime oldCutoff);

    @Update("UPDATE compute_member_session SET expires_at=TIMESTAMPADD(HOUR,#{hours},last_seen_at) WHERE expires_at>#{now} AND last_seen_at>#{oldCutoff}")
    int updateActiveMemberExpiry(@Param("hours") int hours, @Param("now") LocalDateTime now,
                                @Param("oldCutoff") LocalDateTime oldCutoff);
}
