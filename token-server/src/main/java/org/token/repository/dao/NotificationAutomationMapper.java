package org.token.repository.dao;

import org.apache.ibatis.annotations.*;
import org.token.model.query.MemberScope;
import org.token.repository.system.NotificationAutomationQueries;
import java.time.LocalDateTime;
import java.util.*;

@Mapper
public interface NotificationAutomationMapper {
    @Select("SELECT setting_value AS config_json,revision,updated_by,updated_at FROM system_setting WHERE setting_key='notification.automation.configuration'") Map<String,Object> configuration();
    @Select("SELECT setting_value AS config_json,revision,updated_by,updated_at FROM system_setting WHERE setting_key='notification.automation.configuration' FOR UPDATE") Map<String,Object> lockConfiguration();
    @Update("UPDATE system_setting SET setting_value=#{json},revision=revision+1,updated_by=#{uid},updated_at=#{now} WHERE setting_key='notification.automation.configuration' AND revision=#{version}")
    int saveConfiguration(@Param("json") String json,@Param("uid") int uid,@Param("now") LocalDateTime now,@Param("version") long version);
    @InsertProvider(type=NotificationAutomationQueries.class,method="collectFinanceEvents")
    int collectFinanceEvents(@Param("withdrawal") boolean withdrawal,@Param("since") LocalDateTime since,@Param("now") LocalDateTime now);
    @SelectProvider(type=NotificationAutomationQueries.class,method="financeEvents")
    List<Map<String,Object>> financeEvents(@Param("withdrawal") boolean withdrawal,@Param("scope") MemberScope scope,@Param("after") Long after,@Param("maximum") boolean maximum,@Param("limit") int limit,@Param("since") LocalDateTime since);
    @SelectProvider(type=NotificationAutomationQueries.class,method="candidates")
    List<Map<String,Object>> candidates(@Param("event") String event,@Param("since") LocalDateTime since,@Param("before") LocalDateTime before,@Param("threshold") java.math.BigDecimal threshold);
    @SelectProvider(type=NotificationAutomationQueries.class,method="eligible")
    long eligible(@Param("event") String event,@Param("source") long source,@Param("since") LocalDateTime since,@Param("before") LocalDateTime before,@Param("threshold") java.math.BigDecimal threshold);
    @Select("SELECT a.uid,a.locale,r.role permissions,r.isSelf is_super FROM ad_master a JOIN user_role r ON r.id=a.role WHERE a.state=1 ORDER BY a.uid")
    List<Map<String,Object>> administrators();
    @Select("SELECT a.uid,a.locale,r.role permissions,r.isSelf is_super FROM ad_master a JOIN user_role r ON r.id=a.role WHERE a.state=1 AND a.uid=#{uid}")
    Map<String,Object> administrator(@Param("uid") int uid);
    @Select("SELECT COUNT(*) FROM notification_event_delivery WHERE event_code=#{event} AND source_key=#{source} AND target_key=#{target}")
    long delivered(@Param("event") String event,@Param("source") String source,@Param("target") String target);
    @Insert("INSERT INTO notification_event_delivery(event_code,source_key,target_key,status,created_at,updated_at) VALUES(#{event},#{source},#{target},'CLAIMED',#{now},#{now})")
    int claim(@Param("event") String event,@Param("source") String source,@Param("target") String target,@Param("now") LocalDateTime now);
    @Update("UPDATE notification_event_delivery SET status=#{status},updated_at=#{now} WHERE event_code=#{event} AND source_key=#{source} AND target_key=#{target}")
    int complete(@Param("event") String event,@Param("source") String source,@Param("target") String target,@Param("status") String status,@Param("now") LocalDateTime now);
    @Select("SELECT source_key FROM notification_automation_state WHERE event_code='energyLow' FOR UPDATE") List<String> energyState();
    @Insert("INSERT INTO notification_automation_state(event_code,source_key,updated_at) VALUES('energyLow',#{source},#{now}) ON DUPLICATE KEY UPDATE source_key=#{source},updated_at=#{now}")
    int energyStateSet(@Param("source") String source,@Param("now") LocalDateTime now);
}
