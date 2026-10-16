package org.token.repository.system;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/** 基础实体持久化操作；事务由调用它的业务服务持有。 */
public abstract class EntityRepository<T, M extends BaseMapper<T>> {
    protected final M mapper;

    protected EntityRepository(M mapper) { this.mapper = mapper; }

    public T getById(Serializable id) { return mapper.selectById(id); }
    public boolean save(T entity) { return mapper.insert(entity) == 1; }
    public boolean updateById(T entity) { return mapper.updateById(entity) == 1; }
    public boolean removeById(Serializable id) { return mapper.deleteById(id) == 1; }
    public List<T> list() { return mapper.selectList(null); }
    public List<T> listByIds(Collection<? extends Serializable> ids) {
        return ids == null || ids.isEmpty() ? List.of() : mapper.selectBatchIds(ids);
    }
    public boolean saveBatch(Collection<T> entities) {
        boolean result = true;
        for (T entity : entities) result &= save(entity);
        return result;
    }
    public boolean updateBatchById(Collection<T> entities) {
        boolean result = true;
        for (T entity : entities) result &= updateById(entity);
        return result;
    }
}
