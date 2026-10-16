package org.token.repository.dao.compute;

import org.token.repository.compute.ComputeRewardQueries;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeRewardService 的参数化数据库语句。 */
@Mapper
public interface ComputeRewardMapper {

    @Select("SELECT asset_code currency FROM compute_currency_asset WHERE asset_code='USDT' ORDER BY sort_no,id")
    List<Map<String, Object>> settingsCurrencyAssetRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_currency_asset WHERE asset_code=#{args[0]}")
    List<Map<String, Object>> saveCurrencyAssetCount(@Param("args") Object[] args);

    @Update("UPDATE system_setting SET setting_value=#{args[0]},revision=revision+1,updated_at=#{args[1]},updated_by=#{args[2]} WHERE setting_key='compute.reward.configuration' AND revision=#{args[3]}")
    int saveRewardConfigurationWrite(@Param("args") Object[] args);

    @Select("SELECT d.*,m.is_test FROM compute_deposit d JOIN compute_member m ON m.id=d.member_id WHERE d.id=#{args[0]}")
    List<Map<String, Object>> onDepositApprovedDepositRow(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_ledger WHERE member_id=#{args[0]} AND event_key=#{args[1]} AND kind='DEPOSIT' AND currency=#{args[2]} AND amount=#{args[3]}")
    List<Map<String, Object>> onDepositApprovedLedgerCount(@Param("args") Object[] args);

    @Select("SELECT l.*,m.is_test FROM compute_ledger l JOIN compute_member m ON m.id=l.member_id WHERE l.member_id=#{args[0]} AND l.event_key=#{args[1]}")
    List<Map<String, Object>> onIncomePostedLedgerRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_reward_award WHERE member_id=#{args[0]} AND rule_key=#{args[1]}")
    List<Map<String, Object>> effectiveRewardsRewardAwardCount(@Param("args") Object[] args);

    @Select("SELECT l.reward_rules_json FROM compute_member m JOIN compute_level l ON l.id=m.level AND l.enabled=1 WHERE m.id=#{args[0]} AND (m.vip_expires_at IS NULL OR m.vip_expires_at>#{args[1]})")
    List<Map<String, Object>> vipRewardsMemberRows(@Param("args") Object[] args);

    @Select("SELECT enabled,is_test FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> awardMemberRow(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_reward_award WHERE event_key=#{args[0]} AND member_id=#{args[1]} AND rule_key=#{args[2]}")
    List<Map<String, Object>> awardRewardAwardCount(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen,updated_at) VALUES(#{args[0]},'EARNINGS',#{args[1]},0,0,#{args[2]}) ON DUPLICATE KEY UPDATE id=id")
    int awardWalletWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_wallet WHERE member_id=#{args[0]} AND wallet_type='EARNINGS' AND currency=#{args[1]} FOR UPDATE")
    List<Map<String, Object>> awardWalletRow(@Param("args") Object[] args);

    @Update("UPDATE compute_wallet SET available=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]}")
    int awardWalletWrite2(@Param("args") Object[] args);

    @Select("SELECT level,is_test FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> centerMemberRow(@Param("args") Object[] args);

    @Select("SELECT l.reward_rules_json FROM compute_level l JOIN compute_member m ON m.level=l.id WHERE m.id=#{args[0]} AND l.enabled=1 AND (m.vip_expires_at IS NULL OR m.vip_expires_at>#{args[1]})")
    List<Map<String, Object>> centerLevelRows(@Param("args") Object[] args);

    @Select("SELECT currency,SUM(amount) amount FROM compute_reward_award WHERE member_id=#{args[0]} GROUP BY currency")
    List<Map<String, Object>> centerRewardAwardRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_reward_award WHERE member_id=#{args[0]}")
    List<Map<String, Object>> recordsRewardAwardCount(@Param("args") Object[] args);

    @Select("SELECT COALESCE(SUM(amount),0) AS repository_value FROM compute_reward_award WHERE member_id=#{args[0]} AND source_kind=#{args[1]} AND currency=#{args[2]}")
    List<Map<String, Object>> totalRewardAwardValue(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_deposit WHERE member_id=#{args[0]} AND status='APPROVED' ORDER BY COALESCE(reviewed_at,created_at),id LIMIT 1")
    List<Map<String, Object>> firstDepositDepositRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_reward_event WHERE event_key=#{args[0]}")
    List<Map<String, Object>> processedRewardEventCount(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_reward_event(event_key,created_at) VALUES(#{args[0]},#{args[1]})")
    int markRewardEventWrite(@Param("args") Object[] args);

    @Select("SELECT setting_value AS configuration_json,revision,updated_by,updated_at FROM system_setting WHERE setting_key='compute.reward.configuration'")
    List<Map<String, Object>> configurationRewardConfigurationRow(@Param("args") Object[] args);
    @SelectProvider(type=ComputeRewardQueries.class,method="qualifiedCount")
    long qualifiedCount(@Param("owner") long owner,@Param("currency") String currency,@Param("customer") Long customer,@Param("first") boolean first,@Param("threshold") java.math.BigDecimal threshold);
    @Select("""
          SELECT r.order_no,r.source_kind,r.currency,r.amount,r.depth,r.created_at,m.username source_username,r.is_virtual
          FROM compute_reward_award r JOIN compute_member m ON m.id=r.source_member_id WHERE r.member_id=#{memberId}
          ORDER BY r.created_at DESC,r.order_no DESC LIMIT #{limit} OFFSET #{offset}
        """)
    List<Map<String,Object>> recordsPage(@Param("memberId") long memberId,@Param("limit") int limit,@Param("offset") long offset);
}
