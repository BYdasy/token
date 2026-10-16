package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeRewardEligibility 的参数化数据库语句。 */
@Mapper
public interface ComputeRewardEligibilityMapper {

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> lockMembersMemberRow(@Param("args") Object[] args);

    @Select("SELECT available FROM compute_wallet WHERE member_id=#{args[0]} AND wallet_type='COMPUTE' AND currency=#{args[1]} FOR UPDATE")
    List<Map<String, Object>> balanceWalletRows(@Param("args") Object[] args);

    @Select("SELECT COALESCE(SUM(s.usd_amount),0) AS repository_value\nFROM compute_deposit d\nJOIN compute_member m ON m.id=d.member_id\nJOIN compute_finance_snapshot s ON s.business_type='DEPOSIT' AND s.business_id=d.id\n     AND s.member_id=d.member_id AND s.usd_amount IS NOT NULL\nWHERE d.member_id=#{args[0]} AND d.status='APPROVED'\n")
    List<Map<String, Object>> successfulDepositTokenDepositValue(@Param("args") Object[] args);
}
