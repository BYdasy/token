package org.token.repository.dao;

import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.token.model.entity.OperationLog;
import java.util.List;

/** 日志单表分页复用 MyBatis-Plus；历史类型选项使用去重注解查询。 */
@Mapper
public interface OperationLogMapper extends MPJBaseMapper<OperationLog> {
    @Select("SELECT DISTINCT type FROM ad_record WHERE type IS NOT NULL AND type<>'' ORDER BY type")
    List<String> actionTypes();
}
