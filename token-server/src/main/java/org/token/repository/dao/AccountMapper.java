package org.token.repository.dao;

import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.token.model.entity.Account;

/**
 * 后台用户主表 Mapper
 *
 * MPJBaseMapper 已经包含 BaseMapper 基础能力。
 */
@Mapper
public interface AccountMapper extends MPJBaseMapper<Account> {

    @Select("SELECT * FROM ad_master WHERE username=#{username} LIMIT 1")
    org.token.model.entity.Account findByUsername(String username);
    @Select("SELECT COUNT(*) FROM ad_master WHERE role=#{roleId}")
    int countByRoleId(Integer roleId);
    @Insert("INSERT INTO admin_security(admin_uid,updated_at) VALUES(#{uid},#{now})")
    int createSecurity(@Param("uid") Integer uid,@Param("now") java.time.LocalDateTime now);
    @Delete("DELETE FROM admin_security WHERE admin_uid=#{uid}")
    int deleteSecurity(Integer uid);
    @Delete("DELETE FROM system_notification WHERE recipient_uid=#{uid}")
    int deleteReceivedNotifications(Integer uid);
    @Update("UPDATE system_notification SET sender_uid=NULL WHERE sender_uid=#{uid}")
    int detachSentNotifications(Integer uid);


    @Select("<script>SELECT uid,username FROM ad_master WHERE uid IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    List<org.token.model.entity.Account> namesByIds(@Param("ids") Collection<Integer> ids);
    @Select("SELECT uid,username FROM ad_master WHERE state=1 ORDER BY username ASC,uid ASC")
    List<org.token.model.entity.Account> enabledTargets();
    @Select("""
        <script>SELECT uid FROM ad_master WHERE state=1
        <choose><when test="targetType == 'USER'">AND uid IN <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach></when>
        <when test="targetType == 'ROLE'">AND role IN <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach></when></choose></script>
        """)
    List<org.token.model.entity.Account> recipients(@Param("targetType") String targetType,@Param("ids") Collection<Integer> ids);

}
