package org.token.repository.system;

import org.token.repository.dao.TranslationCompletionTaskMapper;
import org.token.model.entity.TranslationCompletionTask;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** TranslationCompletionTask 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class TranslationCompletionTaskRepository extends EntityRepository<TranslationCompletionTask, TranslationCompletionTaskMapper> {
    public TranslationCompletionTaskRepository(TranslationCompletionTaskMapper mapper) { super(mapper); }

    public TranslationCompletionTask findOwned(Long id,Integer uid) { return mapper.findOwned(id,uid); }
    public List<TranslationCompletionTask> findActive() { return mapper.findActive(); }
    public TranslationCompletionTask findActiveByType(Integer type) { return mapper.findActiveByType(type); }
    public int start(Long id,java.time.LocalDateTime now) { return mapper.start(id,now); }
    public int progress(Long id,int percent,org.token.model.response.TranslationCompletionProgress progress,java.time.LocalDateTime now) { return mapper.progress(id,percent,progress,now); }
    public int complete(Long id,org.token.model.response.TranslationCompletionResult result,java.time.LocalDateTime now) { return mapper.complete(id,result,now); }
    public int fail(Long id,String error,java.time.LocalDateTime now) { return mapper.fail(id,error,now); }

}
