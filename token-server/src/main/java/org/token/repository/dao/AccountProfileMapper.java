package org.token.repository.dao;

import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.token.model.entity.AccountProfile;

/** 账号主表资料投影的局部更新 Mapper。 */
@Mapper
public interface AccountProfileMapper extends MPJBaseMapper<AccountProfile> {
}
