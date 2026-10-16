package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import org.token.model.query.MemberScope;
import org.token.repository.compute.ComputeLiveMonitorRepository;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeLiveMonitorService 的参数化数据库语句。 */
@Mapper
public interface ComputeLiveMonitorMapper {

    @Insert("INSERT INTO system_setting(setting_key,setting_value,updated_at,group_name) VALUES(#{args[0]},#{args[1]},#{args[2]},'compute') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),updated_at=VALUES(updated_at),revision=revision+1")
    int saveSettingWrite(@Param("args") Object[] args);

    @SelectProvider(type=ComputeLiveMonitorRepository.Queries.class, method="online")
    Long onlineCount(@Param("observedAt") LocalDateTime observedAt, @Param("onlineSince") LocalDateTime onlineSince, @Param("idleSince") LocalDateTime idleSince, @Param("scope") MemberScope scope);
    @SelectProvider(type=ComputeLiveMonitorRepository.Queries.class, method="withdrawals")
    Long pendingWithdrawals(@Param("scope") MemberScope scope);
}
