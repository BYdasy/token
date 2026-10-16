package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeUsdAccounting 的参数化数据库语句。 */
@Mapper
public interface ComputeUsdAccountingMapper {

    @Select("SELECT asset_type FROM compute_currency_asset WHERE asset_code=#{args[0]}")
    List<Map<String, Object>> strictQuoteCurrencyAssetRows(@Param("args") Object[] args);

    @Select("SELECT rate,provider,source_at,refreshed_at FROM compute_exchange_rate WHERE rate_type=#{args[0]} AND currency=#{args[1]}")
    List<Map<String, Object>> strictQuoteExchangeRateRows(@Param("args") Object[] args);

    @Select("<script>SELECT business_id,usd_rate,usd_amount,usd_fee,usd_net_amount,snapshot_source FROM compute_finance_snapshot WHERE business_type=#{kind} AND business_id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    List<Map<String, Object>> legacySnapshots(@Param("kind") String kind, @Param("ids") Collection<Long> ids);
}
