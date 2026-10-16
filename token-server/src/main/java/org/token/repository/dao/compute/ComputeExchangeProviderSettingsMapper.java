package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeExchangeProviderSettings 的参数化数据库语句。 */
@Mapper
public interface ComputeExchangeProviderSettingsMapper {

    @Select("SELECT setting_key,setting_value FROM system_setting WHERE group_name='compute' ORDER BY setting_key FOR UPDATE")
    List<Map<String, Object>> lockConfigurationSettingRows(@Param("args") Object[] args);

    @Insert("INSERT INTO system_setting(setting_key,setting_value,updated_at,group_name) VALUES(#{args[0]},#{args[1]},#{args[2]},'compute') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),updated_at=VALUES(updated_at),revision=revision+1")
    int saveSettingWrite(@Param("args") Object[] args);
}
