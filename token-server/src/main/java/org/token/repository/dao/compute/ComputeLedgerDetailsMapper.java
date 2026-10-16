package org.token.repository.dao.compute;

import java.util.*;
import org.token.model.query.MemberScope;
import org.token.repository.compute.ComputeLedgerDetailsRepository;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeLedgerDetails 的参数化数据库语句。 */
@Mapper
public interface ComputeLedgerDetailsMapper {

    @Select("SELECT order_no,currency,amount,accounting_rate,accounting_amount FROM compute_deposit WHERE id=#{args[0]} AND member_id=#{args[1]} AND status='APPROVED'")
    List<Map<String, Object>> rewardFactsDepositRows(@Param("args") Object[] args);

    @SelectProvider(type=ComputeLedgerDetailsRepository.Queries.class, method="sources")
    List<Map<String,Object>> sources(@Param("table") String table, @Param("ids") Collection<Long> ids);
    @SelectProvider(type=ComputeLedgerDetailsRepository.Queries.class, method="visibleNames")
    List<Map<String,Object>> visibleNames(@Param("ids") Collection<Long> ids, @Param("scope") MemberScope scope);
    @SelectProvider(type=ComputeLedgerDetailsRepository.Queries.class, method="incomeSources")
    List<Map<String,Object>> incomeSources(@Param("ids") Collection<Long> ids);
    @SelectProvider(type=ComputeLedgerDetailsRepository.Queries.class, method="paymentSources")
    List<Map<String,Object>> paymentSources(@Param("ids") Collection<Long> ids);
}
