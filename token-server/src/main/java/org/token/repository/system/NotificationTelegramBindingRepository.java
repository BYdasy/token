package org.token.repository.system;

import org.token.model.entity.NotificationChannelConfig;
import org.token.model.entity.NotificationTelegramBinding;
import org.token.repository.dao.NotificationTelegramBindingMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class NotificationTelegramBindingRepository extends EntityRepository<NotificationTelegramBinding, NotificationTelegramBindingMapper> {
    public NotificationTelegramBindingRepository(NotificationTelegramBindingMapper mapper) { super(mapper); }
    public List<NotificationTelegramBinding> findBindings(Long channelId, boolean enabledOnly) { return mapper.findBindings(channelId, enabledOnly); }
    public Page<NotificationTelegramBinding> findPage(int current, int size, Long channelId, String keyword) {
        return mapper.findPage(new Page<>(current, size), channelId, keyword);
    }
    public NotificationChannelConfig lockChannel(Long id) { return mapper.lockChannel(id); }
    public NotificationTelegramBinding lockBinding(Long id) { return mapper.lockBinding(id); }
    public NotificationTelegramBinding findByChat(Long channelId, String chatId) { return mapper.findByChat(channelId, chatId); }
}
