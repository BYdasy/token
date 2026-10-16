package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.token.model.entity.IpWhitelistEntry;

@Mapper
public interface IpWhitelistMapper extends BaseMapper<IpWhitelistEntry> {

    @Select("SELECT * FROM ip_whitelist ORDER BY updated_at DESC,id DESC")
    List<org.token.model.entity.IpWhitelistEntry> findAllOrdered();
    @Select("SELECT * FROM ip_whitelist WHERE enabled=1")
    List<org.token.model.entity.IpWhitelistEntry> enabled();

}
