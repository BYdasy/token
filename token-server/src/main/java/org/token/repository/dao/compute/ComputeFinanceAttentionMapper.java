package org.token.repository.dao.compute;

import org.apache.ibatis.annotations.*;
import org.token.model.query.MemberScope;
import org.token.repository.compute.ComputeFinanceAttentionQueries;
import java.util.*;

@Mapper
public interface ComputeFinanceAttentionMapper {
    @SelectProvider(type=ComputeFinanceAttentionQueries.class,method="snapshot")
    List<Map<String,Object>> snapshot(@Param("withdrawal") boolean withdrawal,@Param("gateway") boolean gateway,
        @Param("merchantId") String merchantId,@Param("environment") String environment,@Param("scope") MemberScope scope);
}
