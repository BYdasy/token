package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.token.model.entity.Role;

/**
 * 后台角色 Mapper
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    @Select("SELECT id,name,remarks,actionTime,isSelf AS isSuper,role FROM user_role ORDER BY actionTime DESC,id ASC")
    List<org.token.model.entity.Role> findAllOrdered();
    @Select("SELECT id,name,remarks,actionTime,isSelf AS isSuper,role FROM user_role WHERE name=#{name} LIMIT 1")
    org.token.model.entity.Role findByName(String name);
    @SelectProvider(type=SystemPageSql.class, method="roles")
    Page<org.token.model.entity.Role> findPage(Page<org.token.model.entity.Role> page, @Param("query") QueryParams query);


    @Select("SELECT id,name FROM user_role ORDER BY id ASC")
    List<org.token.model.entity.Role> targets();
    @Select("SELECT id FROM user_role WHERE isSelf=1")
    List<org.token.model.entity.Role> superRoleIds();

}
