package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeContentService 的参数化数据库语句。 */
@Mapper
public interface ComputeContentMapper {

    @SelectProvider(type = ComputeContentQuerySql.class, method = "select")
    List<Map<String, Object>> queryRows(Map<String, Object> parameters);

    @SelectProvider(type = ComputeContentQuerySql.class, method = "count")
    Long queryCount(Map<String, Object> parameters);

    @Select("<script>SELECT username FROM compute_member WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id</script>")
    List<String> usernames(@Param("ids") List<Long> ids);

    @Select("<script>SELECT id FROM compute_member WHERE username IN <foreach collection='names' item='name' open='(' separator=',' close=')'>#{name}</foreach> AND enabled=1 ORDER BY id</script>")
    List<Long> resolveUsernames(@Param("names") List<String> names);

    @Update("UPDATE compute_content SET enabled=0,updated_at=#{args[0]} WHERE id=#{args[1]}")
    int activateLockedContentWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_content SET enabled=1,status='PUBLISHED',error_key=NULL,updated_at=#{args[0]} WHERE id=#{args[1]}")
    int activateLockedContentWrite2(@Param("args") Object[] args);

    @Select("SELECT COALESCE(MAX(id),0) AS repository_value FROM compute_member")
    List<Map<String, Object>> activateLockedMemberValue(@Param("args") Object[] args);

    @Update("UPDATE compute_content SET enabled=1,status='PUBLISHING',audience_max_id=COALESCE(audience_max_id,#{args[0]}),error_key=NULL,updated_at=#{args[1]} WHERE id=#{args[2]}")
    int activateLockedContentWrite3(@Param("args") Object[] args);

    @Update("UPDATE compute_content SET status='FAILED',error_key='server.compute.content.queueFull',updated_at=#{args[0]} WHERE id=#{args[1]} AND status='PUBLISHING' AND enabled=1")
    int completeChangeContentWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_content SET enabled=0,status='RECALLED',updated_at=#{args[0]} WHERE id=#{args[1]}")
    int recallContentWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_content SET deleted=1,updated_at=#{args[0]} WHERE id=#{args[1]}")
    int deleteContentWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_content_recipient SET restriction_released_at=#{args[0]},restriction_released_by=#{args[1]} WHERE content_id=#{args[2]} AND member_id=#{args[3]} AND restriction_released_at IS NULL")
    int releaseRestrictionContentRecipientWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_content WHERE kind='ANNOUNCEMENT' AND home_popup_enabled=1 AND deleted=0 AND enabled=1 AND status='PUBLISHED' AND (starts_at IS NULL OR starts_at<=#{args[0]}) AND (ends_at IS NULL OR ends_at>#{args[1]}) ORDER BY pinned DESC,sort DESC,COALESCE(starts_at,created_at) DESC,id DESC LIMIT 20")
    List<Map<String, Object>> publicHomePopupsContentRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_content WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> markHomePopupContentRow(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_content_popup_seen(content_id,member_id,content_version,seen_at) SELECT #{args[0]},#{args[1]},#{args[2]},#{args[3]} WHERE NOT EXISTS(SELECT 1 FROM compute_content_popup_seen WHERE content_id=#{args[4]} AND member_id=#{args[5]} AND content_version=#{args[6]})")
    int markHomePopupContentPopupSeenWrite(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_content WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> markContentRows(@Param("args") Object[] args);

    @Update("UPDATE compute_content_recipient SET read_at=#{args[0]} WHERE content_id=#{args[1]} AND member_id=#{args[2]} AND read_at IS NULL")
    int markContentRecipientWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_content_recipient SET completed_at=#{args[0]} WHERE content_id=#{args[1]} AND member_id=#{args[2]} AND completed_at IS NULL")
    int markContentRecipientWrite2(@Param("args") Object[] args);

    @Update("UPDATE compute_content SET read_count=read_count+#{args[0]},completed_count=completed_count+#{args[1]} WHERE id=#{args[2]}")
    int markContentWrite(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_member WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> deliverMemberCount(@Param("args") Object[] args);

    @Update("UPDATE compute_content SET status='PUBLISHED',updated_at=#{args[0]} WHERE id=#{args[1]}")
    int deliverContentWrite(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_content_recipient(content_id,member_id,created_at) SELECT #{args[0]},#{args[1]},#{args[2]} WHERE NOT EXISTS (SELECT 1 FROM compute_content_recipient WHERE content_id=#{args[3]} AND member_id=#{args[4]})")
    int deliverContentRecipientWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_content SET recipient_count=recipient_count+#{args[0]},processed_id=#{args[1]},updated_at=#{args[2]} WHERE id=#{args[3]}")
    int deliverContentWrite2(@Param("args") Object[] args);

    @Update("UPDATE compute_content SET status='FAILED',error_key='server.compute.content.deliveryFailed',updated_at=#{args[0]} WHERE id=#{args[1]} AND status='PUBLISHING'")
    int deliverContentWrite3(@Param("args") Object[] args);

    @Update("UPDATE compute_content SET status='FAILED',error_key='server.compute.content.interrupted',updated_at=#{args[0]} WHERE status='PUBLISHING' AND enabled=1 AND updated_at<#{args[1]}")
    int recoverInterruptedDeliveriesContentWrite(@Param("args") Object[] args);

    @Select("SELECT username AS repository_value FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> decodeMemberRows(@Param("args") Object[] args);

    @Select("SELECT owner_member_id FROM compute_member_group WHERE id=#{args[0]}")
    List<Map<String, Object>> validateAudienceOwnerMemberGroupRow(@Param("args") Object[] args);
}
