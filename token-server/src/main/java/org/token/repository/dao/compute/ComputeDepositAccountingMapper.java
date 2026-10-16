package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeDepositAccounting 的参数化数据库语句。 */
@Mapper
public interface ComputeDepositAccountingMapper {

    @Update("UPDATE compute_deposit SET accounting_version=2,accounting_currency='USD',accounting_amount=NULL,accounting_rate=NULL,accounting_quote_json=NULL,accounting_status='RATE_PENDING' WHERE id=#{args[0]} AND status='PENDING'")
    int prepareDepositWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_deposit SET accounting_version=2,accounting_currency='USD',accounting_amount=NULL,accounting_rate=#{args[0]},accounting_quote_json=#{args[1]},accounting_status='AMOUNT_PRECISION' WHERE id=#{args[2]} AND status='PENDING'")
    int prepareDepositWrite2(@Param("args") Object[] args);

    @Update("UPDATE compute_deposit SET accounting_version=2,accounting_currency='USD',accounting_amount=#{args[0]},accounting_rate=#{args[1]},accounting_quote_json=#{args[2]},accounting_status='PENDING' WHERE id=#{args[3]} AND status='PENDING'")
    int prepareDepositWrite3(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_deposit WHERE id=#{args[0]}")
    List<Map<String, Object>> prepareDepositRow(@Param("args") Object[] args);

    @Select("SELECT accounting_status FROM compute_deposit WHERE id=#{args[0]}")
    List<Map<String, Object>> pendingReasonDepositRow(@Param("args") Object[] args);
}
