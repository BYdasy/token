package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeUserTimeZoneService 的参数化数据库语句。 */
@Mapper
public interface ComputeUserTimeZoneMapper {

    @Update("UPDATE compute_member SET user_time_zone=#{args[0]} WHERE id=#{args[1]} AND enabled=1")
    int saveMemberWrite(@Param("args") Object[] args);
}
