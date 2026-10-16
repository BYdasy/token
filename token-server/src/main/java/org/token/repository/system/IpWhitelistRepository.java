package org.token.repository.system;

import org.token.repository.dao.IpWhitelistMapper;
import org.token.model.entity.IpWhitelistEntry;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** IpWhitelistEntry 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class IpWhitelistRepository extends EntityRepository<IpWhitelistEntry, IpWhitelistMapper> {
    public IpWhitelistRepository(IpWhitelistMapper mapper) { super(mapper); }

    public List<IpWhitelistEntry> findAllOrdered() { return mapper.findAllOrdered(); }
    public List<IpWhitelistEntry> enabled() { return mapper.enabled(); }

}
