package org.token.repository.system;

import org.token.repository.dao.NotificationTemplateMapper;
import org.token.model.entity.NotificationTemplate;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** NotificationTemplate 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class NotificationTemplateRepository extends EntityRepository<NotificationTemplate, NotificationTemplateMapper> {
    public NotificationTemplateRepository(NotificationTemplateMapper mapper) { super(mapper); }

    public List<NotificationTemplate> findAllOrdered() { return mapper.findAllOrdered(); }
    public Page<NotificationTemplate> findPage(int current, int size, String keyword, String channelType) {
        return mapper.findPage(new Page<>(current, size), keyword, channelType);
    }
    public NotificationTemplate findDuplicate(String code,Long excludedId) { return mapper.findDuplicate(code,excludedId); }
    public NotificationTemplate findEnabled(String code) { return mapper.findEnabled(code); }

}
