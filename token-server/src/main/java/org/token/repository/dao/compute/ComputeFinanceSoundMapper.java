package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeFinanceSoundService 的参数化数据库语句。 */
@Mapper
public interface ComputeFinanceSoundMapper {

    @Select("SELECT revision,updated_at FROM compute_finance_sound_config WHERE id=1")
    List<Map<String, Object>> settingsFinanceSoundConfigRow(@Param("args") Object[] args);

    @Select("SELECT revision,config_json,updated_at FROM compute_finance_sound_config WHERE id=1")
    List<Map<String, Object>> settingsFinanceSoundConfigRow2(@Param("args") Object[] args);

    @Update("UPDATE compute_finance_sound_config SET config_json=#{args[0]},revision=revision+1,updated_by=#{args[1]},updated_at=#{args[2]} WHERE id=1 AND revision=#{args[3]}")
    int saveFinanceSoundConfigWrite(@Param("args") Object[] args);
}
