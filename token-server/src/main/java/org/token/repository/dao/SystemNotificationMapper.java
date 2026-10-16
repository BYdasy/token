package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.token.model.entity.SystemNotification;

@Mapper
public interface SystemNotificationMapper extends BaseMapper<SystemNotification> {

    @Select("""
        <script>SELECT * FROM system_notification <where>
        <if test="uid != null">recipient_uid=#{uid} <if test="!includeHidden">AND hidden_status=0</if></if>
        <if test="status != null">AND read_status=#{status}</if>
        <if test="search != null">AND (title LIKE CONCAT('%',#{search},'%') OR content LIKE CONCAT('%',#{search},'%'))</if>
        <if test="start != null and start != ''">AND created_at &gt;= #{start}</if>
        <if test="end != null and end != ''">AND created_at &lt;= #{end}</if>
        </where> ORDER BY created_at DESC,id DESC</script>
        """)
    Page<org.token.model.entity.SystemNotification> findPage(Page<org.token.model.entity.SystemNotification> page,
        @Param("uid") Integer uid,@Param("includeHidden") boolean includeHidden,@Param("status") Integer status,
        @Param("search") String search,@Param("start") String start,@Param("end") String end);
    @Select("SELECT COUNT(*) FROM system_notification WHERE recipient_uid=#{uid} AND hidden_status=0 AND read_status=0")
    long unreadCount(Integer uid);
    @Update("UPDATE system_notification SET read_status=1,read_time=#{now} WHERE id=#{id} AND recipient_uid=#{uid} AND read_status=0")
    int markRead(@Param("uid") Integer uid,@Param("id") Long id,@Param("now") java.time.LocalDateTime now);
    @Update("UPDATE system_notification SET read_status=1,read_time=#{now} WHERE recipient_uid=#{uid} AND hidden_status=0 AND read_status=0")
    int markAllRead(@Param("uid") Integer uid,@Param("now") java.time.LocalDateTime now);
    @Update("UPDATE system_notification SET hidden_status=1,read_status=1,read_time=#{now} WHERE id=#{id} AND recipient_uid=#{uid}")
    int hide(@Param("uid") Integer uid,@Param("id") Long id,@Param("now") java.time.LocalDateTime now);
    @Update("UPDATE system_notification SET hidden_status=1,read_status=1,read_time=#{now} WHERE recipient_uid=#{uid} AND hidden_status=0")
    int clear(@Param("uid") Integer uid,@Param("now") java.time.LocalDateTime now);

}
