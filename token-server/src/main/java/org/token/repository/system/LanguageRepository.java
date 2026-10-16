package org.token.repository.system;

import org.token.repository.dao.LanguageMapper;
import org.token.model.entity.Language;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Language 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class LanguageRepository extends EntityRepository<Language, LanguageMapper> {
    public LanguageRepository(LanguageMapper mapper) { super(mapper); }

    public List<Language> findOrdered(boolean enabledOnly) { return mapper.findOrdered(enabledOnly); }
    public Page<Language> findPage(long current,long size,QueryParams query,String pattern) { return mapper.findPage(new Page<>(current,size),query,pattern); }

}
