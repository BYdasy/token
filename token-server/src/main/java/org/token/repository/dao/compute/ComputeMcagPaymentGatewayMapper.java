package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeMcagPaymentGateway 的参数化数据库语句。 */
@Mapper
public interface ComputeMcagPaymentGatewayMapper {

    @Select("SELECT id,is_test FROM compute_member WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> getOrCreateAddressMemberRow(@Param("args") Object[] args);

    @Select("SELECT d.id,d.accounting_model,d.collection_json,d.state,d.reason FROM compute_mcag_collection_batch d WHERE d.merchant_id=#{args[0]} AND d.environment=#{args[1]} AND d.batch_id=#{args[2]}")
    List<Map<String, Object>> requestReconciliationMcagCollectionBatchRows(@Param("args") Object[] args);

    String PAYOUT_REVIEW_FROM = " FROM compute_mcag_payout p JOIN compute_withdrawal w ON w.id=p.withdrawal_id JOIN compute_member m ON m.id=w.member_id WHERE p.merchant_id=#{merchant} AND p.environment=#{environment} AND (p.state='REVIEW' OR p.reason<>'')";

    @Select("SELECT COUNT(*)" + PAYOUT_REVIEW_FROM)
    List<Map<String, Object>> payoutStatusMcagPayoutCount(@Param("merchant") String merchant, @Param("environment") String environment);

    @Select("SELECT p.payout_id gateway_reference,p.merchant_order_id local_order_no,m.id uid,m.username,p.currency,p.network,'EARNINGS' wallet_type,p.amount_text,p.state,p.submission_state,p.remote_status,p.reason,p.attempts,p.next_attempt_at,p.updated_at" + PAYOUT_REVIEW_FROM + " ORDER BY p.updated_at DESC,p.id DESC LIMIT #{pageSize} OFFSET #{offset}")
    List<Map<String, Object>> payoutStatusMcagPayoutRows(@Param("merchant") String merchant, @Param("environment") String environment,
                                                     @Param("pageSize") int pageSize, @Param("offset") long offset);

    @Select("SELECT order_before_id FROM compute_mcag_cursor WHERE merchant_id=#{args[0]} AND environment=#{args[1]}")
    List<Map<String, Object>> watchOrdersMcagCursorRow(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_cursor SET order_before_id=#{args[0]},updated_at=#{args[1]} WHERE merchant_id=#{args[2]} AND environment=#{args[3]} AND COALESCE(order_before_id,'')=#{args[4]}")
    int watchOrdersMcagCursorWrite(@Param("args") Object[] args);

    @Select("SELECT o.id,o.external_order_id,f.currency FROM compute_mcag_deposit_order o\nJOIN compute_deposit f ON f.id=o.record_id\nWHERE o.merchant_id=#{args[0]} AND o.environment=#{args[1]} AND o.state='REVIEW'\n  AND o.reason='RATE_UNAVAILABLE' AND o.local_deposit_id IS NULL\n  AND f.status='PENDING' AND f.accounting_status='RATE_PENDING'\n  AND (f.currency IN ('USD','USDT','USDC','USDT0') OR EXISTS (\n    SELECT 1 FROM compute_currency_asset a JOIN compute_exchange_rate r\n      ON r.currency=a.asset_code AND r.rate_type=CASE WHEN a.asset_type='FIAT' THEN 'FIAT' ELSE 'ASSET' END\n    WHERE a.asset_code=f.currency AND r.rate>0))\nORDER BY o.updated_at,o.id LIMIT 5\n")
    List<Map<String, Object>> retryPricedDepositsMcagDepositOrderRows(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_deposit_order SET updated_at=#{args[0]} WHERE id=#{args[1]} AND state='REVIEW' AND reason='RATE_UNAVAILABLE' AND local_deposit_id IS NULL")
    int retryPricedDepositsMcagDepositOrderWrite(@Param("args") Object[] args);

    @Select("SELECT batch_before_id FROM compute_mcag_cursor WHERE merchant_id=#{args[0]} AND environment=#{args[1]}")
    List<Map<String, Object>> watchBatchesMcagCursorRow(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_cursor SET batch_before_id=#{args[0]},updated_at=#{args[1]} WHERE merchant_id=#{args[2]} AND environment=#{args[3]} AND COALESCE(batch_before_id,'')=#{args[4]}")
    int watchBatchesMcagCursorWrite(@Param("args") Object[] args);

    @Select("SELECT merchant_order_id,amount_text,destination_address,currency,network FROM compute_mcag_payout WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND merchant_order_id=#{args[2]}")
    List<Map<String, Object>> createPayoutMcagPayoutRow(@Param("args") Object[] args);

    @SelectProvider(type = ComputeMcagDepositReviewSql.class, method = "count")
    List<Map<String, Object>> depositReviewCount(@Param("merchant") String merchant, @Param("environment") String environment);

    @SelectProvider(type = ComputeMcagDepositReviewSql.class, method = "page")
    List<Map<String, Object>> depositReviews(@Param("merchant") String merchant, @Param("environment") String environment,
                                           @Param("pageSize") int pageSize, @Param("offset") long offset);

    @Select("SELECT COALESCE(MAX(after_sequence),0) FROM compute_mcag_cursor WHERE merchant_id=#{merchant} AND environment=#{environment}")
    List<Map<String, Object>> depositAfterSequence(@Param("merchant") String merchant, @Param("environment") String environment);

    @Select("SELECT d.state,d.reason,d.remote_status,d.snapshot_json,d.record_id,d.local_deposit_id,f.order_no local_order_no,f.record_source FROM compute_mcag_deposit_order d LEFT JOIN compute_deposit f ON f.id=COALESCE(d.record_id,d.local_deposit_id) WHERE d.merchant_id=#{merchant} AND d.environment=#{environment} AND d.external_order_id=#{reference}")
    List<Map<String, Object>> reconciliationDepositOrder(@Param("merchant") String merchant, @Param("environment") String environment, @Param("reference") String reference);
}
