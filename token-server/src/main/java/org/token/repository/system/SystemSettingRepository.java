package org.token.repository.system;

import org.token.repository.dao.SystemSettingMapper;
import org.token.model.entity.SystemSetting;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import java.util.*;

/** SystemSetting 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class SystemSettingRepository extends EntityRepository<SystemSetting, SystemSettingMapper> {
    @org.springframework.beans.factory.annotation.Autowired
    public SystemSettingRepository(SystemSettingMapper mapper) { super(mapper); }

    public String selectSystemTimeZone() { return mapper.selectSystemTimeZone(); }
    public String selectServerDisplayTimeZone() { return mapper.selectServerDisplayTimeZone(); }
    public List<SystemSetting> findByKeys(Collection<String> keys) { return keys.isEmpty()?List.of():mapper.findByKeys(keys); }
    public boolean saveOrUpdate(SystemSetting setting) {
        SystemSetting current=getById(setting.getSettingKey());
        setting.setUpdatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        if(current==null){setting.setRevision(0L);return save(setting);}
        setting.setRevision(current.getRevision());
        if(mapper.updateRevision(setting)!=1)
            throw new org.token.exception.BusinessException("server.settings.concurrentUpdate");
        return true;
    }
    public List<SystemSetting> presentationSettings(){return mapper.presentationSettings();}

    public SystemSettingRepository(org.springframework.jdbc.core.JdbcTemplate jdbc) { this(org.token.repository.support.AnnotationMapperFactory.mapper(jdbc,SystemSettingMapper.class)); }
    public List<String> values(String key) { return mapper.values(key); }
    public void updateTranslationRevision(String revision,java.time.LocalDateTime now) { mapper.updateTranslationRevision(revision,now); }
}
