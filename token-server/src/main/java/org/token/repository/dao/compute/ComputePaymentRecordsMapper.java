package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputePaymentRecords 的参数化数据库语句。 */
@Mapper
public interface ComputePaymentRecordsMapper {

    @Select("SELECT network_code,block_explorer_url FROM compute_currency_network")
    List<Map<String, Object>> enrichCurrencyNetworkRows(@Param("args") Object[] args);

    @Select("<script>SELECT id,username FROM compute_member WHERE id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    List<Map<String, Object>> memberNames(@Param("ids") Collection<?> ids);

    @Select("<script>SELECT withdrawal_id AS record_id,payout_id AS gateway_reference,state AS gateway_state,reason AS gateway_reason,attempts AS gateway_attempts,next_attempt_at AS gateway_next_attempt_at,submission_state,remote_status,quote_json,chain_details_json FROM compute_mcag_payout WHERE withdrawal_id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    List<Map<String, Object>> payoutReferences(@Param("ids") Collection<?> ids);

    @Select("<script>SELECT d.record_id,d.external_order_id AS gateway_reference,d.state AS gateway_state,d.reason AS gateway_reason,d.snapshot_json,d.merchant_id,d.environment,d.customer_id,d.address_id,d.asset_id,d.network_id FROM compute_mcag_deposit_order d WHERE d.record_id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    List<Map<String, Object>> orderReferences(@Param("ids") Collection<?> ids);

    @Select("<script>SELECT merchant_id,environment,batch_id,customer_id,address_id,asset_id,network_id,accounting_model,sweep_status,reason,collection_json FROM compute_mcag_collection_batch WHERE <foreach collection='links' item='link' open='(' close=')' separator=' OR '>(merchant_id=#{link.merchantId} AND environment=#{link.environment} AND batch_id=#{link.batchId})</foreach></script>")
    List<Map<String, Object>> orderCollectionBatches(@Param("links") Collection<? extends Map<String, Object>> links);
}
