package org.token.repository.system;

import org.token.repository.dao.MenuMapper;
import org.token.model.entity.Menu;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Menu 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class MenuRepository extends EntityRepository<Menu, MenuMapper> {
    public MenuRepository(MenuMapper mapper) { super(mapper); }
    public List<Menu> enabled(Collection<Integer> retained, Collection<Integer> ids) { return mapper.enabled(retained,ids); }
}
