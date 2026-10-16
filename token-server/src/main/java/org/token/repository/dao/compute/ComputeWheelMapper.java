package org.token.repository.dao.compute;

import org.token.repository.compute.ComputeWheelQueries;
import org.token.repository.compute.ComputeActivityQualificationQueries;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeWheelService 的参数化数据库语句。 */
@Mapper
public interface ComputeWheelMapper {

    @Select("SELECT COUNT(*) AS repository_value FROM compute_member_group WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> normalizeMemberGroupCount(@Param("args") Object[] args);

    @Select("SELECT DISTINCT target_range_id FROM compute_wheel_member WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND control_type='INDEPENDENT' AND target_draw>used_count AND target_range_id IS NOT NULL")
    List<Map<String, Object>> assertChestTargetsUnchangedWheelMemberRows(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_wheel_member(activity_id,member_id,round_no,created_at,updated_at) SELECT #{args[0]},#{args[1]},#{args[2]},#{args[3]},#{args[4]} WHERE NOT EXISTS (SELECT 1 FROM compute_wheel_member WHERE activity_id=#{args[5]} AND member_id=#{args[6]} AND round_no=#{args[7]})")
    int syncWheelMemberWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_wheel_member WHERE activity_id=#{args[0]} AND member_id=#{args[1]} AND round_no=#{args[2]} FOR UPDATE")
    List<Map<String, Object>> syncWheelMemberRow(@Param("args") Object[] args);

    @Update("UPDATE compute_wheel_member SET chest_earned_offset=#{args[0]} WHERE activity_id=#{args[1]} AND member_id=#{args[2]} AND round_no=#{args[3]}")
    int syncWheelMemberWrite2(@Param("args") Object[] args);

    @Update("UPDATE compute_wheel_member SET earned_count=GREATEST(earned_count,#{args[0]}),pending_count=pending_count+#{args[1]}-#{args[2]},available_count=available_count+#{args[3]},issued_count=issued_count+#{args[4]},updated_at=#{args[5]} WHERE activity_id=#{args[6]} AND member_id=#{args[7]} AND round_no=#{args[8]}")
    int syncWheelMemberWrite3(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_wheel_member WHERE activity_id=#{args[0]} AND member_id=#{args[1]} AND round_no=#{args[2]}")
    List<Map<String, Object>> syncWheelMemberRow2(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} AND enabled=1 FOR UPDATE")
    List<Map<String, Object>> memberStatusMemberRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> memberStatusActivityRow(@Param("args") Object[] args);

    @Select("SELECT group_id FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> requireAttemptsMemberRow(@Param("args") Object[] args);

    @Update("UPDATE compute_wheel_member SET available_count=available_count-1,used_count=used_count+1,updated_at=#{args[0]} WHERE activity_id=#{args[1]} AND member_id=#{args[2]} AND round_no=#{args[3]} AND available_count>0")
    int consumeWheelMemberWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_wheel_adjustment WHERE activity_id=#{args[0]} AND member_id=#{args[1]} AND request_id=#{args[2]}")
    List<Map<String, Object>> adjustWheelAdjustmentRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_activity_prize WHERE activity_id=#{args[0]} AND id=#{args[1]} AND probability>0")
    List<Map<String, Object>> adjustActivityPrizeCount(@Param("args") Object[] args);

    @Update("UPDATE compute_wheel_member SET available_count=#{args[0]},issued_count=issued_count+#{args[1]},pending_count=GREATEST(0,pending_count-#{args[2]}),control_type=#{args[3]},target_draw=#{args[4]},target_prize_id=#{args[5]},target_range_id=#{args[6]},updated_at=#{args[7]} WHERE activity_id=#{args[8]} AND member_id=#{args[9]} AND round_no=#{args[10]}")
    int adjustWheelMemberWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_wheel_adjustment WHERE id=#{args[0]}")
    List<Map<String, Object>> adjustWheelAdjustmentRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity_participation WHERE id=#{args[0]}")
    List<Map<String, Object>> redeemActivityParticipationRow(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> redeemMemberRow(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_activity WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> redeemActivityRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity_participation WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> redeemActivityParticipationRow2(@Param("args") Object[] args);

    @Update("UPDATE compute_activity_participation SET status=#{args[0]},redemption_type=#{args[1]},redeemed_at=#{args[2]},redeemed_by=#{args[3]} WHERE id=#{args[4]}")
    int redeemActivityParticipationWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_activity SET wheel_config=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]}")
    int resetActivityWrite(@Param("args") Object[] args);

    @Select("SELECT id,name,probability FROM compute_activity_prize WHERE activity_id=#{args[0]} ORDER BY sort_order,id")
    List<Map<String, Object>> membersActivityPrizeRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_activity_attempt_grant WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND source_member_id=#{args[2]} AND beneficiary_role='LEGACY'")
    List<Map<String, Object>> syncTierGrantsActivityAttemptGrantCount(@Param("args") Object[] args);

    @Select("SELECT COALESCE(SUM(awarded_count),0) AS repository_value FROM compute_activity_attempt_grant WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND beneficiary_member_id=#{args[2]} AND beneficiary_role=#{args[3]}")
    List<Map<String, Object>> grantCountActivityAttemptGrantValue(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity_attempt_grant WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND (beneficiary_member_id=#{args[2]} OR source_member_id IN(SELECT id FROM compute_member WHERE inviter_id=#{args[3]}))")
    List<Map<String, Object>> tierTotalActivityAttemptGrantRows(@Param("args") Object[] args);

    @Select("SELECT COALESCE(SUM(earned_count),0) AS repository_value FROM compute_wheel_member WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND member_id=#{args[2]}")
    List<Map<String, Object>> tierTotalWheelMemberValue(@Param("args") Object[] args);

    @Select("SELECT COALESCE(SUM(awarded_count),0) AS repository_value FROM compute_activity_attempt_grant WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND beneficiary_member_id=#{args[2]} AND beneficiary_role IN('SELF','INVITER')")
    List<Map<String, Object>> recordedChestGrantsActivityAttemptGrantValue(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity_attempt_grant WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND beneficiary_member_id=#{args[2]} AND beneficiary_role IN('SELF','INVITER')")
    List<Map<String, Object>> chestTierTotalActivityAttemptGrantRows(@Param("args") Object[] args);

    @Select("SELECT earned_count,chest_earned_offset FROM compute_wheel_member WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND member_id=#{args[2]}")
    List<Map<String, Object>> chestTierTotalWheelMemberRows(@Param("args") Object[] args);
    @SelectProvider(type=ComputeWheelQueries.class,method="membersCount")
    long membersCount(Map<String,Object> parameters);
    @SelectProvider(type=ComputeWheelQueries.class,method="membersRows")
    List<Map<String,Object>> membersRows(Map<String,Object> parameters);
    @SelectProvider(type=ComputeWheelQueries.class,method="statisticsCurrencies")
    List<Map<String,Object>> statisticsCurrencies(Map<String,Object> parameters);
    @SelectProvider(type=ComputeWheelQueries.class,method="statisticsPrizes")
    List<Map<String,Object>> statisticsPrizes(Map<String,Object> parameters);
    @SelectProvider(type=ComputeActivityQualificationQueries.class,method="earned")
    java.math.BigDecimal earned(Map<String,Object> parameters);
    @SelectProvider(type=ComputeActivityQualificationQueries.class,method="wheelTiers")
    List<Map<String,Object>> wheelTiers(Map<String,Object> parameters);
    @SelectProvider(type=ComputeActivityQualificationQueries.class,method="chestTiers")
    List<Map<String,Object>> chestTiers(Map<String,Object> parameters);
    @SelectProvider(type=ComputeActivityQualificationQueries.class,method="priorGrants")
    List<Map<String,Object>> priorGrants(Map<String,Object> parameters);
}
