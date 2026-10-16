package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ComputeOnlineMapper {

    @Select("<script>SELECT DISTINCT s.member_id FROM compute_member_session s JOIN compute_member m ON m.id=s.member_id WHERE s.expires_at&gt;#{observedAt} AND s.last_seen_at&gt;=#{onlineSince} AND s.last_seen_at&gt;#{idleSince} AND s.credential_hash=m.password_hash AND m.enabled=1 AND s.member_id IN <foreach collection='members' item='member' open='(' close=')' separator=','>#{member}</foreach></script>")
    List<Long> onlineMemberIds(@Param("members") Collection<Long> members, @Param("observedAt") LocalDateTime observedAt, @Param("onlineSince") LocalDateTime onlineSince, @Param("idleSince") LocalDateTime idleSince);
}
