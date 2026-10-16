package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeProductPurchasePolicy 的参数化数据库语句。 */
@Mapper
public interface ComputeProductPurchasePolicyMapper {

    @Select("SELECT amount,currency,purchase_quantity,purchase_amount_token,product_quote_json FROM compute_order WHERE member_id=#{args[0]} AND product_id=#{args[1]} AND product_type='GPU' ORDER BY id")
    List<Map<String, Object>> quotaOrderRows(@Param("args") Object[] args);
}
