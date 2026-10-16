package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.apache.ibatis.annotations.Select;
import org.token.model.entity.SystemSetting;

@Mapper
public interface SystemSettingMapper extends BaseMapper<SystemSetting> {
    String COLUMNS="setting_key AS settingKey,setting_value AS settingValue,value_type AS valueType,group_name AS groupName,description,is_public AS isPublic,is_editable AS isEditable,updated_by AS updatedBy,revision,updated_at AS updatedAt";
    /** 时区影响资金次数、自然日和调度，跨实例直接读取主键，不能使用节点的展示缓存副本。 */
    @Select("SELECT setting_value FROM system_setting WHERE setting_key='system.time-zone'")
    String selectSystemTimeZone();
    @Select("SELECT setting_value FROM system_setting WHERE setting_key='server.display-time-zone'")
    String selectServerDisplayTimeZone();

    @Select("<script>SELECT "+COLUMNS+" FROM system_setting WHERE setting_key IN <foreach collection='keys' item='key' open='(' close=')' separator=','>#{key}</foreach></script>")
    List<org.token.model.entity.SystemSetting> findByKeys(@Param("keys") Collection<String> keys);


    @Select("SELECT setting_value FROM system_setting WHERE setting_key=#{key}")
    List<String> values(String key);
    @Update("UPDATE system_setting SET setting_value=#{revision},revision=revision+1,updated_at=#{now} WHERE setting_key='i18n.translations.revision'")
    int updateTranslationRevision(@Param("revision") String revision,@Param("now") java.time.LocalDateTime now);

    @Update("""
        UPDATE system_setting SET setting_value=#{settingValue},value_type=#{valueType},group_name=#{groupName},
          description=#{description},is_public=#{isPublic},is_editable=#{isEditable},updated_by=#{updatedBy},
          updated_at=#{updatedAt},revision=revision+1 WHERE setting_key=#{settingKey} AND revision=#{revision}
        """)
    int updateRevision(SystemSetting setting);

    @Select("SELECT "+COLUMNS+" FROM system_setting WHERE group_name IN ('site','user-site','user-share','system','cache') ORDER BY setting_key")
    List<SystemSetting> presentationSettings();

}
