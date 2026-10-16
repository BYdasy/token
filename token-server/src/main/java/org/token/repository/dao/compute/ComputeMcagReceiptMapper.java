package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeMcagReceiptService 的参数化数据库语句。 */
@Mapper
public interface ComputeMcagReceiptMapper {

    @Select("SELECT id,is_test FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> applyMemberRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_address WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> applyMcagAddressRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_cursor WHERE merchant_id=#{args[0]} AND environment=#{args[1]} FOR UPDATE")
    List<Map<String, Object>> applyMcagCursorRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_deposit_order WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND external_order_id=#{args[2]} FOR UPDATE")
    List<Map<String, Object>> applyMcagDepositOrderRows(@Param("args") Object[] args);

    @Select("SELECT external_order_id FROM compute_mcag_deposit_order WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND (receipt_id=#{args[2]} OR transfer_event_id=#{args[3]} OR (network_id=#{args[4]} AND asset_id=#{args[5]} AND tx_hash=#{args[6]} AND transfer_locator=#{args[7]}))")
    List<Map<String, Object>> applyMcagDepositOrderRows2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_deposit WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> applyDepositRow(@Param("args") Object[] args);

    @Update("UPDATE compute_deposit SET payment_status=#{args[0]},sender_address=#{args[1]},receiver_address=#{args[2]},receiver_memo=#{args[3]},tx_hash=#{args[4]},chain_status=#{args[5]},chain_confirmations=#{args[6]},chain_updated_at=#{args[7]} WHERE id=#{args[8]}")
    int applyDepositWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_deposit SET payment_status=#{args[0]} WHERE id=#{args[1]}")
    int applyDepositWrite2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_address WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND customer_id=#{args[2]} AND asset_id=#{args[3]} AND network_id=#{args[4]} AND address_id=#{args[5]}")
    List<Map<String, Object>> bindingMcagAddressRows(@Param("args") Object[] args);

    @Select("SELECT id,is_test FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> bindingMemberRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_address WHERE id=#{args[0]}")
    List<Map<String, Object>> bindingMcagAddressRow(@Param("args") Object[] args);
}
