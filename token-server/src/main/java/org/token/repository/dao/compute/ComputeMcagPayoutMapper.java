package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeMcagPayoutService 的参数化数据库语句。 */
@Mapper
public interface ComputeMcagPayoutMapper {

    @Select("SELECT * FROM compute_mcag_payout WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND state NOT IN ('SUCCEEDED','RELEASED') AND next_attempt_at<=#{args[2]} AND (lease_until IS NULL OR lease_until<#{args[3]}) ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
    List<Map<String, Object>> claimMcagPayoutRows(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET lease_token=#{args[0]},lease_until=#{args[1]},attempts=attempts+1,updated_at=#{args[2]} WHERE id=#{args[3]}")
    int claimMcagPayoutWrite(@Param("args") Object[] args);

    @Select("SELECT member_id FROM compute_withdrawal WHERE id=#{args[0]}")
    List<Map<String, Object>> executeWithdrawalRow(@Param("args") Object[] args);

    @Select("SELECT is_test FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> executeMemberRow(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET quote_id=#{args[0]},quote_json=#{args[1]},quote_expires_at=#{args[2]},state='QUOTED',reason='',updated_at=#{args[3]} WHERE id=#{args[4]} AND lease_token=#{args[5]} AND state='NEW' AND quote_id IS NULL")
    int executeMcagPayoutWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET state='CREATING',submission_state='IN_FLIGHT',updated_at=#{args[0]} WHERE id=#{args[1]} AND lease_token=#{args[2]} AND state IN ('QUOTED','CREATING','RETRY_WAIT')")
    int executeMcagPayoutWrite2(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET quote_id=NULL,quote_json=NULL,quote_expires_at=NULL,state='NEW',submission_state='NOT_SENT',reason='QUOTE_REFRESH_REQUIRED',updated_at=#{args[0]} WHERE id=#{args[1]} AND lease_token=#{args[2]} AND state='CREATING' AND payout_id IS NULL")
    int executeMcagPayoutWrite3(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET submission_state='REJECTED' WHERE id=#{args[0]} AND lease_token=#{args[1]} AND state='CREATING' AND payout_id IS NULL")
    int executeMcagPayoutWrite4(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET state='NEW',submission_state='NOT_SENT',quote_id=NULL,quote_json=NULL,quote_expires_at=NULL,reason='',next_attempt_at=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]} AND state='REVIEW' AND submission_state IN ('NOT_SENT','REJECTED') AND payout_id IS NULL AND (lease_until IS NULL OR lease_until<#{args[3]})")
    int reconcileMcagPayoutWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_payout WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> cancelUnsubmittedMcagPayoutRow(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET lease_token=#{args[0]},lease_until=#{args[1]},updated_at=#{args[2]} WHERE id=#{args[3]}")
    int cancelUnsubmittedMcagPayoutWrite(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> cancelUnsubmittedMemberRow(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_withdrawal WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> cancelUnsubmittedWithdrawalRow(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET reviewed_by=#{args[0]},remark=#{args[1]} WHERE id=#{args[2]}")
    int cancelUnsubmittedWithdrawalWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET state='RELEASED',reason='LOCAL_CANCELLED',updated_at=#{args[0]} WHERE id=#{args[1]}")
    int cancelUnsubmittedMcagPayoutWrite2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_payout WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND merchant_order_id=#{args[2]}")
    List<Map<String, Object>> applyMcagPayoutRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_withdrawal WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> applyWithdrawalRow(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET payout_id=#{args[0]},submission_state='ACCEPTED',resource_version=#{args[1]},remote_status=#{args[2]},state=#{args[3]},reason=#{args[4]},chain_details_json=#{args[5]},updated_at=#{args[6]} WHERE id=#{args[7]}")
    int applyMcagPayoutWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET external_order_no=#{args[0]},payment_status=#{args[1]},chain_status=#{args[2]},chain_updated_at=#{args[3]},receiver_address=#{args[4]},tx_hash=COALESCE(NULLIF(#{args[5]},''),tx_hash),sender_address=COALESCE(NULLIF(#{args[6]},''),sender_address) WHERE id=#{args[7]}")
    int applyWithdrawalWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET chain_confirmations=#{args[0]} WHERE id=#{args[1]}")
    int applyWithdrawalWrite2(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET state='REVIEW',reason=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]} AND lease_token=#{args[3]} AND state NOT IN ('SUCCEEDED','RELEASED')")
    int reviewMcagPayoutWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET payment_status='MCAG_REVIEW' WHERE id=#{args[0]} AND status='PROCESSING'")
    int reviewWithdrawalWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET state='RETRY_WAIT',submission_state='REJECTED',reason='MERCHANT_POOL_UNAVAILABLE',updated_at=#{args[0]} WHERE id=#{args[1]} AND lease_token=#{args[2]} AND payout_id IS NULL AND quote_id IS NOT NULL AND quote_id<>'' AND resource_version=0 AND remote_status='' AND ((state='CREATING' AND submission_state='IN_FLIGHT') OR (state='REVIEW' AND submission_state='REJECTED' AND reason='MERCHANT_POOL_UNAVAILABLE'))")
    int waitForFundsMcagPayoutWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET payment_status='MCAG_QUEUED' WHERE id=#{args[0]} AND status='PROCESSING' AND completion_mode='MCAG_PENDING'")
    int waitForFundsWithdrawalWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET reason=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]} AND lease_token=#{args[3]} AND state NOT IN ('SUCCEEDED','RELEASED')")
    int retryMcagPayoutWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_payout SET lease_token=NULL,lease_until=NULL,next_attempt_at=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]} AND lease_token=#{args[3]}")
    int releaseMcagPayoutWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_payout WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND (merchant_order_id=#{args[2]} OR payout_id=#{args[3]})")
    List<Map<String, Object>> localMcagPayoutRow(@Param("args") Object[] args);
}
