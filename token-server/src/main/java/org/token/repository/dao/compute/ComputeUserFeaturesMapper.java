package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeUserFeaturesService 的参数化数据库语句。 */
@Mapper
public interface ComputeUserFeaturesMapper {

    @Select("SELECT name FROM compute_activity WHERE id=#{args[0]}")
    List<Map<String, Object>> adminSettingsActivityRows(@Param("args") Object[] args);

    @Insert("INSERT INTO system_setting(setting_key,setting_value,updated_at,group_name) VALUES(#{args[0]},#{args[1]},#{args[2]},'compute') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),updated_at=VALUES(updated_at),revision=revision+1")
    int putSettingWrite(@Param("args") Object[] args);

    @Select("SELECT id,name,type,wheel_config,floating_enabled,floating_image,floating_priority,ends_at FROM compute_activity WHERE enabled=1 AND starts_at<=#{args[0]} AND ends_at>#{args[1]} AND (type<>'WHEEL' OR id=#{args[2]}) ORDER BY floating_priority DESC,id")
    List<Map<String, Object>> publicSettingsActivityRows(@Param("args") Object[] args);

    @Select("SELECT m.user_language,l.image_url avatar_url FROM compute_member m LEFT JOIN compute_level l ON l.id=m.level WHERE m.id=#{args[0]} AND m.enabled=1")
    List<Map<String, Object>> memberPreferencesMemberRow(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM translate_language WHERE lang=#{args[0]} AND status=1")
    List<Map<String, Object>> languageTranslateLanguageCount(@Param("args") Object[] args);

    @Update("UPDATE compute_member SET user_language=#{args[0]} WHERE id=#{args[1]} AND enabled=1 AND (user_language IS NULL OR user_language<>#{args[2]})")
    int languageMemberWrite(@Param("args") Object[] args);

    @Select("SELECT setting_value FROM system_setting WHERE setting_key='userFeatures' FOR UPDATE")
    List<String> lockFeatureSettings();
}
