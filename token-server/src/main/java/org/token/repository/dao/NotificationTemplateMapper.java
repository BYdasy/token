package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.token.model.entity.NotificationTemplate;

@Mapper
public interface NotificationTemplateMapper extends BaseMapper<NotificationTemplate> {

    @Select("SELECT * FROM notification_template ORDER BY id DESC")
    List<org.token.model.entity.NotificationTemplate> findAllOrdered();
    @Select("<script>SELECT * FROM notification_template WHERE 1=1 "
            + "<if test='channelType != null and channelType != &quot;&quot;'>AND channel_type=#{channelType}</if> "
            + "<if test='keyword != null and keyword != &quot;&quot;'>AND (LOWER(code) LIKE CONCAT('%',LOWER(#{keyword}),'%') "
            + "OR LOWER(CONCAT('T',id)) LIKE CONCAT('%',LOWER(#{keyword}),'%') OR LOWER(name) LIKE CONCAT('%',LOWER(#{keyword}),'%') "
            + "OR LOWER(channel_type) LIKE CONCAT('%',LOWER(#{keyword}),'%') OR LOWER(content_template) LIKE CONCAT('%',LOWER(#{keyword}),'%'))</if> "
            + "ORDER BY id DESC</script>")
    Page<NotificationTemplate> findPage(Page<NotificationTemplate> page, @Param("keyword") String keyword, @Param("channelType") String channelType);
    @Select("<script>SELECT * FROM notification_template WHERE code=#{code} <if test='excludedId != null'>AND id != #{excludedId}</if> LIMIT 1</script>")
    org.token.model.entity.NotificationTemplate findDuplicate(@Param("code") String code,@Param("excludedId") Long excludedId);
    @Select("SELECT * FROM notification_template WHERE code=#{code} AND enabled=1 LIMIT 1")
    org.token.model.entity.NotificationTemplate findEnabled(String code);

}
