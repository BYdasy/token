package org.token.repository.dao.compute;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.token.model.entity.ComputeMemberGroup;

/** 稳定分组配置使用 MyBatis-Plus 基础 CRUD，不复制通用增改查 SQL。 */
@Mapper
public interface ComputeMemberGroupMapper extends BaseMapper<ComputeMemberGroup> { }
