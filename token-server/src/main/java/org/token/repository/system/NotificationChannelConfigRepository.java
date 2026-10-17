package org.token.repository.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.token.repository.dao.NotificationChannelConfigMapper;
import org.token.model.entity.NotificationChannelConfig;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** NotificationChannelConfig 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class NotificationChannelConfigRepository extends EntityRepository<NotificationChannelConfig, NotificationChannelConfigMapper> {
    public NotificationChannelConfigRepository(NotificationChannelConfigMapper mapper) { super(mapper); }

    public List<NotificationChannelConfig> findAllOrdered() { return mapper.findAllOrdered(); }
    public Page<NotificationChannelConfig> findPage(int current, int size, String keyword, String channelType) {
        var query = new LambdaQueryWrapper<NotificationChannelConfig>()
                .eq(!channelType.isEmpty(), NotificationChannelConfig::getChannelType, channelType)
                .and(!keyword.isEmpty(), filter -> filter.like(NotificationChannelConfig::getName, keyword)
                        .or().like(NotificationChannelConfig::getChannelType, keyword)
                        .or().like(NotificationChannelConfig::getDefaultRecipients, keyword))
                .orderByDesc(NotificationChannelConfig::getId);
        return mapper.selectPage(new Page<>(current, size), query);
    }
    public List<NotificationChannelConfig> findChannelOptions() {
        return mapper.selectList(new LambdaQueryWrapper<NotificationChannelConfig>()
                .select(NotificationChannelConfig::getId, NotificationChannelConfig::getName, NotificationChannelConfig::getChannelType)
                .orderByDesc(NotificationChannelConfig::getId));
    }
    public List<NotificationChannelConfig> telegram(boolean enabledOnly) { return mapper.telegram(enabledOnly); }
    public int touch(Long id,java.time.LocalDateTime now) { return mapper.touch(id,now); }
    public int replaceConfig(Long id,String previous,String next,java.time.LocalDateTime now) { return mapper.replaceConfig(id,previous,next,now); }
    public int claimFailureAlert(Long id,java.time.LocalDateTime now,java.time.LocalDateTime cutoff) { return mapper.claimFailureAlert(id,now,cutoff); }

}
