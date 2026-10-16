package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeWithdrawalReviewPolicy 的参数化数据库语句。 */
@Mapper
public interface ComputeWithdrawalReviewPolicyMapper {

    @Insert("INSERT INTO system_setting(setting_key,setting_value,updated_at,group_name) VALUES(#{args[0]},#{args[1]},#{args[2]},'compute') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),updated_at=VALUES(updated_at),revision=revision+1")
    int saveSettingWrite(@Param("args") Object[] args);
}
