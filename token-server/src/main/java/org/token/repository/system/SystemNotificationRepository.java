package org.token.repository.system;

import org.token.repository.dao.SystemNotificationMapper;
import org.token.model.entity.SystemNotification;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** SystemNotification 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class SystemNotificationRepository extends EntityRepository<SystemNotification, SystemNotificationMapper> {
    public SystemNotificationRepository(SystemNotificationMapper mapper) { super(mapper); }

    public Page<SystemNotification> findPage(Page<SystemNotification> page,Integer uid,boolean includeHidden,Integer status,String search,String start,String end) { return mapper.findPage(page,uid,includeHidden,status,search,start,end); }
    public long unreadCount(Integer uid) { return mapper.unreadCount(uid); }
    public void markRead(Integer uid,Long id,java.time.LocalDateTime now) { mapper.markRead(uid,id,now); }
    public void markAllRead(Integer uid,java.time.LocalDateTime now) { mapper.markAllRead(uid,now); }
    public void hide(Integer uid,Long id,java.time.LocalDateTime now) { mapper.hide(uid,id,now); }
    public void clear(Integer uid,java.time.LocalDateTime now) { mapper.clear(uid,now); }

}
