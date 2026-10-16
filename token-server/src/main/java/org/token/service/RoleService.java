package org.token.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.entity.Role;
import org.token.model.request.QueryParams;

import java.util.List;

/**
 * 后台角色服务
 */
public interface RoleService {
    Role getById(java.io.Serializable id);


    /**
     * 分页查询角色列表
     */
    Page<Role> getList(QueryParams params);

    /**
     * 根据 ID 查询角色
     */
    Role getRole(Integer id);

    /**
     * 更新角色
     */
    void updateRole(Role role);

    /**
     * 删除角色
     */
    void deleteRole(Integer id);

    /**
     * 创建角色
     */
    void createRole(String name, String remarks, int isSuper);

    /**
     * 获取全部角色
     */
    List<Role> getAll();
}
