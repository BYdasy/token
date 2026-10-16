package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ComputeContentTaskMapper {

    @Select("<script>SELECT COUNT(*) FROM compute_content c JOIN compute_content_recipient r ON r.content_id=c.id WHERE r.member_id=#{memberId} AND r.restriction_released_at IS NULL AND c.kind='TASK' AND c.deleted=0 AND c.enabled=1 AND c.status='PUBLISHED' AND (c.starts_at IS NULL OR c.starts_at&lt;=#{starts}) AND (c.ends_at IS NULL OR c.ends_at&gt;#{ends}) AND COALESCE(c.task_mode,CASE c.task_type WHEN 'INVESTMENT' THEN 1 WHEN 'WITHDRAWAL' THEN 2 ELSE 4 END) IN <foreach collection='modes' item='mode' open='(' close=')' separator=','>#{mode}</foreach></script>")
    Long activeRestrictions(@Param("memberId") long memberId, @Param("starts") LocalDateTime starts, @Param("ends") LocalDateTime ends, @Param("modes") Collection<Integer> modes);
}
