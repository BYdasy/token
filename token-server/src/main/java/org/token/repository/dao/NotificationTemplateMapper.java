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
    @Select("<script>SELECT * FROM notification_template WHERE code=#{code} <if test='excludedId != null'>AND id != #{excludedId}</if> LIMIT 1</script>")
    org.token.model.entity.NotificationTemplate findDuplicate(@Param("code") String code,@Param("excludedId") Long excludedId);
    @Select("SELECT * FROM notification_template WHERE code=#{code} AND enabled=1 LIMIT 1")
    org.token.model.entity.NotificationTemplate findEnabled(String code);

}
