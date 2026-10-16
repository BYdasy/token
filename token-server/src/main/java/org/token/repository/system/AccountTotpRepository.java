package org.token.repository.system;

import org.token.repository.dao.AccountTotpMapper;
import org.token.model.entity.AccountTotp;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** AccountTotp 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class AccountTotpRepository extends EntityRepository<AccountTotp, AccountTotpMapper> {
    public AccountTotpRepository(AccountTotpMapper mapper) { super(mapper); }

    /** 与登录签发、失败计数、撤销会话一致地先锁账号再更新安全状态。 */
    public void lockAccount(Integer uid) {
        if (mapper.lockAccount(uid) == null) throw new org.token.exception.BusinessException("server.user.notFound");
        mapper.ensureSecurity(uid,org.token.tools.media.SundryUtils.getTime());
    }

    public void resetSecret(Integer uid,String ciphertext) { mapper.resetSecret(uid,ciphertext,org.token.tools.media.SundryUtils.getTime()); }
    public boolean unusedCounter(Integer uid,long counter) { return mapper.unusedCounter(uid,counter,org.token.tools.media.SundryUtils.getTime())>0; }
    public boolean saveOrUpdate(AccountTotp record) { return getById(record.getUid())==null?save(record):updateById(record); }

}
