package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.token.model.entity.Menu;

/**
 * 后台菜单 Mapper。
 */
@Mapper
public interface MenuMapper extends BaseMapper<Menu> {

    @Select("""
        <script>SELECT id,isopen AS isOpen,level,name,pid,sort FROM memu WHERE isopen=1 AND id IN
        <foreach collection="retained" item="id" open="(" close=")" separator=",">#{id}</foreach>
        <if test="ids != null and !ids.isEmpty()">AND id IN <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach></if>
        ORDER BY pid ASC,sort ASC,id ASC</script>
        """)
    List<org.token.model.entity.Menu> enabled(@Param("retained") Collection<Integer> retained,@Param("ids") Collection<Integer> ids);

}
