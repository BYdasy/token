package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.token.model.entity.NotificationChannelConfig;
import org.token.model.entity.NotificationTelegramBinding;
import java.util.List;

@Mapper
public interface NotificationTelegramBindingMapper extends BaseMapper<NotificationTelegramBinding> {
    String COLUMNS = "id,channel_id AS channelId,chat_id AS chatId,name,chat_type AS chatType,enabled,created_at AS createdAt,updated_at AS updatedAt";
    @Select("<script>SELECT b.id,b.channel_id AS channelId,b.chat_id AS chatId,b.name,b.chat_type AS chatType,b.enabled,b.created_at AS createdAt,b.updated_at AS updatedAt FROM notification_telegram_binding b JOIN notification_channel_config c ON c.id=b.channel_id "
            + "WHERE c.channel_type='TELEGRAM' <if test='channelId != null'>AND b.channel_id=#{channelId}</if> "
            + "<if test='enabledOnly'>AND b.enabled=1 AND c.enabled=1</if> ORDER BY b.channel_id,b.id</script>")
    List<NotificationTelegramBinding> findBindings(@Param("channelId") Long channelId, @Param("enabledOnly") boolean enabledOnly);

    @Select("SELECT id,name,channel_type AS channelType,enabled FROM notification_channel_config WHERE id=#{id} FOR UPDATE")
    NotificationChannelConfig lockChannel(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM notification_telegram_binding WHERE id=#{id} FOR UPDATE")
    NotificationTelegramBinding lockBinding(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM notification_telegram_binding WHERE channel_id=#{channelId} AND chat_id=#{chatId}")
    NotificationTelegramBinding findByChat(@Param("channelId") Long channelId, @Param("chatId") String chatId);
}
