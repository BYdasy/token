package org.token.repository.system;

import org.token.repository.dao.RoleMapper;
import org.token.model.entity.Role;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Role 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class RoleRepository extends EntityRepository<Role, RoleMapper> {
    public RoleRepository(RoleMapper mapper) { super(mapper); }

    public List<Role> findAllOrdered() { return mapper.findAllOrdered(); }
    public Role findByName(String name) { return mapper.findByName(name); }
    public Page<Role> findPage(long current, long size, QueryParams query) { return mapper.findPage(new Page<>(current,size),query); }

    public List<Role> targets() { return mapper.targets(); }
    public List<Role> superRoleIds() { return mapper.superRoleIds(); }
}
