package org.token.repository.dao.compute;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** UID 分配配置与新会员自增元数据。 */
@Mapper
public interface ComputeMemberUidMapper {
    @Select("SELECT setting_key,setting_value FROM system_setting WHERE group_name='compute' ORDER BY setting_key FOR UPDATE")
    List<Map<String,Object>> lockAllSettings();
    @Select("SELECT setting_key,setting_value FROM system_setting WHERE setting_key IN ('memberUidStartBase','memberUidRandomStep','memberUidLastAssigned') ORDER BY setting_key")
    List<Map<String,Object>> settings();
    @Select("SELECT setting_key,setting_value FROM system_setting WHERE setting_key IN ('memberUidStartBase','memberUidRandomStep','memberUidLastAssigned') ORDER BY setting_key FOR UPDATE")
    List<Map<String,Object>> lockSettings();
    @Select("SELECT id FROM compute_member ORDER BY id DESC LIMIT 1")
    List<Long> currentMaximum();
    @Insert("INSERT INTO system_setting(setting_key,setting_value,updated_at,group_name) VALUES(#{key},#{value},#{now},'compute') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),updated_at=VALUES(updated_at),revision=revision+1")
    int put(@Param("key") String key,@Param("value") String value,@Param("now") LocalDateTime now);
    @Select("SELECT identity_base FROM information_schema.columns WHERE table_schema=SCHEMA() AND table_name='compute_member' AND column_name='id'")
    Long h2NextIdentity();
    @Select("SELECT @@SESSION.information_schema_stats_expiry")
    long readStatisticsExpiry();
    @Update("SET SESSION information_schema_stats_expiry=#{expiry}")
    void setStatisticsExpiry(@Param("expiry") long expiry);
    @Select("SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_member'")
    Long mysqlNextIdentity();
}
