package org.token.repository.system;

import org.token.repository.dao.AccountMapper;
import org.token.model.entity.Account;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Account 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class AccountRepository extends EntityRepository<Account, AccountMapper> {
    public AccountRepository(AccountMapper mapper) { super(mapper); }

    public Account findByUsername(String username) { return mapper.findByUsername(username); }
    public int countByRoleId(Integer roleId) { return mapper.countByRoleId(roleId); }
    public void createSecurity(Integer uid) { mapper.createSecurity(uid,org.token.tools.media.SundryUtils.getTime()); }
    public void deleteSecurityAndNotificationOwnership(Integer uid) {
        mapper.deleteSecurity(uid); mapper.deleteReceivedNotifications(uid); mapper.detachSentNotifications(uid);
    }

    public List<Account> namesByIds(Collection<Integer> ids) { return ids.isEmpty()?List.of():mapper.namesByIds(ids); }
    public List<Account> enabledTargets() { return mapper.enabledTargets(); }
    public List<Account> recipients(String targetType,Collection<Integer> ids) {
        if (!Set.of("ALL","ROLE","USER").contains(targetType)) throw new IllegalArgumentException("Unsupported recipient filter");
        if (!"ALL".equals(targetType) && ids.isEmpty()) return List.of();
        return mapper.recipients(targetType,ids);
    }
}
