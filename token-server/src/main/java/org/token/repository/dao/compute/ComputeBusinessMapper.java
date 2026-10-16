package org.token.repository.dao.compute;

import java.util.*;
import org.token.repository.compute.ComputeBusinessRepository;
import java.time.*;
import java.math.*;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeBusinessService 的参数化数据库语句。 */
@Mapper
public interface ComputeBusinessMapper {

    @Select("SELECT currency,amount FROM compute_ledger WHERE member_id=#{args[0]} AND event_key=#{args[1]}")
    List<Map<String, Object>> creditActivityRewardLedgerRows(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> initializeWalletsMemberRow(@Param("args") Object[] args);

    @Select("SELECT id,wallet_type AS type,currency,available,frozen FROM compute_wallet WHERE member_id=#{args[0]} AND (currency=#{args[1]} OR available<>0 OR frozen<>0) ORDER BY currency,wallet_type")
    List<Map<String, Object>> walletsWalletRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_order WHERE member_id=#{args[0]} ORDER BY id DESC LIMIT 8")
    List<Map<String, Object>> dashboardOrderRows(@Param("args") Object[] args);


    @Select("SELECT * FROM compute_transfer WHERE id=#{args[0]}")
    List<Map<String, Object>> transferTransferRow(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> transferQuoteMemberRow(@Param("args") Object[] args);

    @Select("SELECT from_wallet,to_wallet FROM compute_transfer_rule WHERE enabled=1 AND currency IN (#{args[0]}, '*') GROUP BY from_wallet,to_wallet ORDER BY from_wallet,to_wallet")
    List<Map<String, Object>> transferOptionsTransferRuleRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_product WHERE id=#{args[0]} AND enabled=1 FOR UPDATE")
    List<Map<String, Object>> buyOrderProductRow(@Param("args") Object[] args);

    @Update("UPDATE compute_order SET purchase_mode=#{args[0]},purchase_quantity=#{args[1]},purchase_unit_price=#{args[2]},purchase_amount_token=#{args[3]} WHERE id=#{args[4]}")
    int buyOrderOrderWrite2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_order WHERE id=#{args[0]}")
    List<Map<String, Object>> buyOrderOrderRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_deposit WHERE id=#{args[0]}")
    List<Map<String, Object>> createDepositDepositRow(@Param("args") Object[] args);

    @Select("SELECT id,level,is_test FROM compute_member WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> quoteMemberRow(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_address WHERE member_id=#{args[0]} AND currency=#{args[1]} AND network=#{args[2]} AND address=#{args[3]} AND memo=#{args[4]} AND enabled=0")
    List<Map<String, Object>> createWithdrawalAddressCount(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_withdrawal WHERE id=#{args[0]}")
    List<Map<String, Object>> createWithdrawalWithdrawalRow(@Param("args") Object[] args);

    @Select("SELECT payment_status,record_source FROM compute_deposit WHERE id=#{args[0]}")
    List<Map<String, Object>> creditMcagDepositDepositRow(@Param("args") Object[] args);

    @Select("SELECT member_id FROM compute_deposit WHERE id=#{args[0]}")
    List<Map<String, Object>> reviewDepositInternalDepositRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_deposit WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> reviewDepositInternalDepositRow2(@Param("args") Object[] args);

    @Update("UPDATE compute_deposit SET status=#{args[0]},remark=#{args[1]},reviewed_by=#{args[2]},reviewed_at=#{args[3]} WHERE id=#{args[4]} AND status='PENDING'")
    int reviewDepositInternalDepositWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_deposit SET accounting_status='POSTED' WHERE id=#{args[0]} AND accounting_version=2")
    int reviewDepositInternalDepositWrite2(@Param("args") Object[] args);

    @Select("SELECT member_id FROM compute_withdrawal WHERE id=#{args[0]}")
    List<Map<String, Object>> reviewWithdrawalWithdrawalRow(@Param("args") Object[] args);

    @Select("SELECT id,is_test FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> reviewWithdrawalMemberRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_withdrawal WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> reviewWithdrawalWithdrawalRow2(@Param("args") Object[] args);

    @Select("SELECT asset_type FROM compute_currency_asset WHERE asset_code=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> reviewWithdrawalCurrencyAssetRows(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET status=#{args[0]},remark=#{args[1]},reviewed_by=#{args[2]},reviewed_at=#{args[3]},completion_mode=#{args[4]},payment_status=#{args[5]} WHERE id=#{args[6]} AND status='PENDING'")
    int reviewWithdrawalWithdrawalWrite(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_mcag_payout WHERE withdrawal_id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> directApproveWithdrawalMcagPayoutRows(@Param("args") Object[] args);

    @Select("SELECT wallet_type,currency,kind,amount,frozen_change,reference_id FROM compute_ledger WHERE member_id=#{args[0]} AND event_key=#{args[1]} FOR UPDATE")
    List<Map<String, Object>> directApproveWithdrawalLedgerRows(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET status='APPROVED',remark=#{args[0]},reviewed_by=#{args[1]},reviewed_at=#{args[2]},completion_mode='DIRECT_CONFIRMED',payment_status='DIRECT_CONFIRMED' WHERE id=#{args[3]} AND status='PENDING'")
    int directApproveWithdrawalWithdrawalWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET status='PROCESSING',remark=#{args[0]},reviewed_by=#{args[1]},reviewed_at=#{args[2]},completion_mode='MCAG_PENDING',payment_status='MCAG_QUEUED',receiver_address=address,chain_status='WAITING' WHERE id=#{args[3]} AND status='PENDING'")
    int queueWithdrawalWithdrawalWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET status=#{args[0]},completion_mode=#{args[1]},payment_status=#{args[2]},reviewed_at=#{args[3]} WHERE id=#{args[4]}")
    int completeMcagWithdrawalWithdrawalWrite(@Param("args") Object[] args);

    @Select("SELECT member_id FROM compute_order WHERE id=#{args[0]}")
    List<Map<String, Object>> settleOrderOrderRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_order WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> settleOrderOrderRow2(@Param("args") Object[] args);

    @Update("UPDATE compute_order SET paid_income=#{args[0]},settled_days=#{args[1]},status=#{args[2]} WHERE id=#{args[3]}")
    int settleOrderOrderWrite(@Param("args") Object[] args);





    @Select("SELECT * FROM compute_address WHERE member_id=#{args[0]} AND currency=#{args[1]} AND network=#{args[2]} AND (address_key=#{args[3]} OR (address_key IS NULL AND LOWER(address)=#{args[4]}))")
    List<Map<String, Object>> saveAddressAddressRows(@Param("args") Object[] args);

    @Update("UPDATE compute_address SET label=#{args[0]},fields_json=#{args[1]},address_key=#{args[2]},enabled=1,removed=0,updated_at=#{args[3]} WHERE id=#{args[4]}")
    int saveAddressAddressWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_address WHERE id=#{args[0]}")
    List<Map<String, Object>> saveAddressAddressRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_address WHERE id=#{args[0]} AND member_id=#{args[1]} FOR UPDATE")
    List<Map<String, Object>> deleteAddressAddressRow(@Param("args") Object[] args);

    @Update("UPDATE compute_address SET removed=1,updated_at=#{args[0]} WHERE id=#{args[1]} AND member_id=#{args[2]}")
    int deleteAddressAddressWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_fee_rule WHERE enabled=1 AND (member_id IS NULL OR member_id=#{args[0]}) AND (level IS NULL OR level=#{args[1]}) AND currency IN (#{args[2]}, '*') AND network IN (#{args[3]}, '*') AND min_amount<=#{args[4]} AND (max_amount IS NULL OR max_amount>=#{args[5]}) ORDER BY CASE rule_type WHEN 'PERSONAL' THEN 0 WHEN 'LEVEL' THEN 1 WHEN 'CHANNEL' THEN 2 WHEN 'AMOUNT' THEN 3 ELSE 4 END,priority DESC,id ASC LIMIT 1")
    List<Map<String, Object>> withdrawalQuoteFeeRuleRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_withdrawal WHERE member_id=#{args[0]} AND currency=#{args[1]} AND network=#{args[2]} AND status IN ('PENDING','PROCESSING','APPROVED') AND created_at>=#{args[3]}")
    List<Map<String, Object>> withdrawalQuoteWithdrawalCount(@Param("args") Object[] args);

    @Select("SELECT available FROM compute_wallet WHERE member_id=#{args[0]} AND wallet_type='EARNINGS' AND currency=#{args[1]}")
    List<Map<String, Object>> withdrawalFundsWalletRows(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET accounting_status=#{args[0]} WHERE id=#{args[1]} AND accounting_version=2 AND accounting_status='FROZEN'")
    int finishWithdrawalAccountingWithdrawalWrite(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen,updated_at) VALUES(#{args[0]},#{args[1]},#{args[2]},0,0,#{args[3]}) ON DUPLICATE KEY UPDATE id=id")
    int ensureWalletWalletWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_wallet WHERE member_id=#{args[0]} AND wallet_type=#{args[1]} AND currency=#{args[2]} FOR UPDATE")
    List<Map<String, Object>> changeWalletWalletRow(@Param("args") Object[] args);

    @Update("UPDATE compute_wallet SET available=#{args[0]},frozen=#{args[1]},updated_at=#{args[2]} WHERE id=#{args[3]}")
    int changeWalletWalletWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_ledger SET operator_uid=#{args[0]},remark=#{args[1]},available_before=available_after-amount,frozen_before=frozen_after-frozen_change WHERE member_id=#{args[2]} AND event_key=#{args[3]}")
    int annotateLedgerLedgerWrite(@Param("args") Object[] args);

    @Select("SELECT pay_password_hash AS repository_value FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> requirePayPasswordMemberValue(@Param("args") Object[] args);

    @Select("SELECT id,level,enabled,is_test FROM compute_member WHERE id=#{args[0]} AND enabled=1 FOR UPDATE")
    List<Map<String, Object>> lockMemberMemberRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_request WHERE member_id=#{args[0]} AND request_id=#{args[1]}")
    List<Map<String, Object>> requestResultRequestRows(@Param("args") Object[] args);

    @Update("UPDATE compute_request SET result_json=#{args[0]} WHERE member_id=#{args[1]} AND request_id=#{args[2]}")
    int finishRequestRequestWrite(@Param("args") Object[] args);

    @Select("SELECT COALESCE(SUM(CASE WHEN status IN ('ACTIVE','RUNNING') THEN amount ELSE 0 END),0) active_principal,COALESCE(SUM(paid_income),0) total_income FROM (SELECT member_id,currency,status,amount,paid_income FROM compute_order UNION ALL SELECT member_id,currency,status,0 amount,paid_income FROM compute_auto_contract) contracts WHERE member_id=#{memberId} AND currency=#{currency}")
    List<Map<String, Object>> dashboardTotals(@Param("memberId") long memberId, @Param("currency") String currency);

    @Select("SELECT currency,COALESCE(SUM(CASE WHEN status IN ('ACTIVE','RUNNING') THEN amount ELSE 0 END),0) active_principal,COALESCE(SUM(paid_income),0) total_income FROM (SELECT member_id,currency,status,amount,paid_income FROM compute_order UNION ALL SELECT member_id,currency,status,0 amount,paid_income FROM compute_auto_contract) contracts WHERE member_id=#{memberId} GROUP BY currency")
    List<Map<String, Object>> currencyTotals(@Param("memberId") long memberId);

    @Select("<script>SELECT * FROM compute_transfer_rule WHERE enabled=1 AND from_wallet=#{from} AND to_wallet=#{to} AND currency IN (#{currency}, '*') AND min_amount&lt;=#{amount} AND max_amount&gt;=#{amount} ORDER BY CASE WHEN currency=#{currency} THEN 0 ELSE 1 END,id LIMIT 1<if test='lock'> FOR UPDATE</if></script>")
    List<Map<String, Object>> transferRules(@Param("from") String from, @Param("to") String to, @Param("currency") String currency, @Param("amount") BigDecimal amount, @Param("lock") boolean lock);

    @Select("<script>SELECT c.support_account_types FROM compute_currency c JOIN compute_currency_asset a ON a.asset_code=c.currency JOIN compute_currency_network n ON n.network_code=c.network WHERE c.currency=#{currency} AND c.enabled=1 AND a.enabled=1 AND n.enabled=1<if test='lock'> FOR UPDATE</if></script>")
    List<Map<String, Object>> transferChannels(@Param("currency") String currency, @Param("lock") boolean lock);

    @Select("<script>SELECT * FROM compute_address WHERE id=#{id} AND member_id=#{memberId} AND currency=#{currency} AND network=#{network} AND enabled=1 AND removed=0<if test='lock'> FOR UPDATE</if></script>")
    List<Map<String, Object>> withdrawalAddress(@Param("id") long id, @Param("memberId") long memberId, @Param("currency") String currency, @Param("network") String network, @Param("lock") boolean lock);

    @Select("<script>SELECT COUNT(*) FROM compute_currency c JOIN compute_currency_asset a ON a.asset_code=c.currency JOIN compute_currency_network n ON n.network_code=c.network WHERE c.currency=#{currency} AND c.enabled=1 AND a.enabled=1 AND n.enabled=1<if test='network != null'> AND c.network=#{network}</if></script>")
    Integer activeCurrencyCount(@Param("currency") String currency, @Param("network") String network);

    @SelectProvider(type=ComputeBusinessRepository.Queries.class, method="dailyIncome")
    List<Map<String,Object>> dailyIncome(@Param("memberId") long memberId,@Param("currency") String currency,@Param("today") LocalDate today,@Param("zone") ZoneId zone);
}
