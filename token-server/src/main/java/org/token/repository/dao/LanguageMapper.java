package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.token.model.entity.Language;

/**
 * 多语言配置 Mapper。
 */
@Mapper
public interface LanguageMapper extends BaseMapper<Language> {

    @Select("<script>SELECT * FROM translate_language <if test='enabledOnly'>WHERE status=1</if> ORDER BY sort_no ASC,id ASC</script>")
    List<org.token.model.entity.Language> findOrdered(@Param("enabledOnly") boolean enabledOnly);
    @SelectProvider(type=SystemPageSql.class, method="languages")
    Page<org.token.model.entity.Language> findPage(Page<org.token.model.entity.Language> page,@Param("query") QueryParams query,@Param("namePattern") String namePattern);

}
