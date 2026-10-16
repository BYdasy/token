package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.token.model.entity.NotificationChannelConfig;

@Mapper
public interface NotificationChannelConfigMapper extends BaseMapper<NotificationChannelConfig> {

    @Select("SELECT * FROM notification_channel_config ORDER BY id DESC")
    List<org.token.model.entity.NotificationChannelConfig> findAllOrdered();
    @Select("<script>SELECT * FROM notification_channel_config WHERE channel_type='TELEGRAM' <if test='enabledOnly'>AND enabled=1 ORDER BY id ASC</if></script>")
    List<org.token.model.entity.NotificationChannelConfig> telegram(@Param("enabledOnly") boolean enabledOnly);
    @Update("UPDATE notification_channel_config SET updated_at=#{now} WHERE id=#{id}")
    int touch(@Param("id") Long id,@Param("now") java.time.LocalDateTime now);
    @Update("UPDATE notification_channel_config SET encrypted_config=#{next},updated_at=#{now} WHERE id=#{id} AND encrypted_config=#{previous}")
    int replaceConfig(@Param("id") Long id,@Param("previous") String previous,@Param("next") String next,@Param("now") java.time.LocalDateTime now);
    @Update("UPDATE notification_channel_config SET last_failure_alert_at=#{now} WHERE id=#{id} AND (last_failure_alert_at IS NULL OR last_failure_alert_at < #{cutoff})")
    int claimFailureAlert(@Param("id") Long id,@Param("now") java.time.LocalDateTime now,@Param("cutoff") java.time.LocalDateTime cutoff);

}
