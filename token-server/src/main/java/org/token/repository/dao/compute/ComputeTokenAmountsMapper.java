package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeTokenAmounts 的参数化数据库语句。 */
@Mapper
public interface ComputeTokenAmountsMapper {

    @Select("SELECT asset_type FROM compute_currency_asset WHERE asset_code=#{args[0]}")
    List<Map<String, Object>> rateCurrencyAssetRows(@Param("args") Object[] args);

    @Select("SELECT rate FROM compute_exchange_rate WHERE rate_type=#{args[0]} AND currency=#{args[1]}")
    List<Map<String, Object>> rateExchangeRateRows(@Param("args") Object[] args);
}
