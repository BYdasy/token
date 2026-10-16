package org.token.repository.dao.compute;

import org.token.repository.compute.ComputeActivityQueries;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeActivityService 的参数化数据库语句。 */
@Mapper
public interface ComputeActivityMapper {

    @Select("SELECT id,name,type,wheel_config FROM compute_activity WHERE type='CHEST' ORDER BY id DESC")
    List<Map<String, Object>> adminPageActivityRows(@Param("args") Object[] args);

    @Select("SELECT id,name,type,wheel_config FROM compute_activity WHERE type='WHEEL' AND id=#{args[0]}")
    List<Map<String, Object>> adminPageActivityRows2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> detailActivityRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity_prize WHERE activity_id=#{args[0]} ORDER BY sort_order,id")
    List<Map<String, Object>> decorateActivityPrizeRows(@Param("args") Object[] args);

    @Select("SELECT wheel_config,type FROM compute_activity WHERE id=#{args[0]}")
    List<Map<String, Object>> decorateActivityRow(@Param("args") Object[] args);

    @Insert("INSERT INTO system_setting(setting_key,setting_value,updated_at,group_name) SELECT 'wheelActivityId','0',#{args[0]},'compute' WHERE NOT EXISTS (SELECT 1 FROM system_setting WHERE setting_key='wheelActivityId')")
    int saveSettingWrite(@Param("args") Object[] args);

    @Select("SELECT setting_value AS repository_value FROM system_setting WHERE setting_key='wheelActivityId' FOR UPDATE")
    List<Map<String, Object>> saveSettingValue(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> saveActivityRow(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_currency WHERE currency=#{args[0]} AND enabled=1")
    List<Map<String, Object>> saveCurrencyCount(@Param("args") Object[] args);

    @Update("UPDATE system_setting SET setting_value=#{args[0]},updated_at=#{args[1]},revision=revision+1 WHERE setting_key='wheelActivityId'")
    int saveSettingWrite2(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_wheel_member WHERE activity_id=#{args[0]} AND round_no=#{args[1]} AND control_type='INDEPENDENT' AND target_draw>used_count AND target_prize_id IS NOT NULL")
    List<Map<String, Object>> saveWheelMemberCount(@Param("args") Object[] args);

    @Delete("DELETE FROM compute_activity_prize WHERE activity_id=#{args[0]}")
    int saveActivityPrizeWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity_prize WHERE activity_id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> saveActivityPrizeRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity WHERE id=#{args[0]}")
    List<Map<String, Object>> saveActivityRow2(@Param("args") Object[] args);

    @Delete("DELETE FROM compute_activity WHERE id=#{args[0]}")
    int deleteActivityWrite(@Param("args") Object[] args);

    @Select("SELECT id,level FROM compute_member WHERE id=#{args[0]} AND enabled=1 FOR UPDATE")
    List<Map<String, Object>> participateMemberRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity_participation WHERE member_id=#{args[0]} AND request_id=#{args[1]}")
    List<Map<String, Object>> participateActivityParticipationRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity_prize WHERE activity_id=#{args[0]} ORDER BY sort_order,id FOR UPDATE")
    List<Map<String, Object>> participateActivityPrizeRows(@Param("args") Object[] args);

    @Update("UPDATE compute_activity_prize SET used_stock=used_stock+1 WHERE id=#{args[0]} AND used_stock<stock")
    int participateActivityPrizeWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_activity SET total_stock=GREATEST(total_stock,used_stock+1),total_budget=GREATEST(total_budget,used_budget+#{args[0]}),used_stock=used_stock+1,used_budget=used_budget+#{args[1]},updated_at=#{args[2]} WHERE id=#{args[3]}")
    int participateActivityWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_activity SET used_stock=used_stock+1,used_budget=used_budget+#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]} AND used_stock<total_stock AND used_budget+#{args[3]}<=total_budget")
    int participateActivityWrite2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_activity_participation WHERE id=#{args[0]}")
    List<Map<String, Object>> participateActivityParticipationRow(@Param("args") Object[] args);

    @Select("SELECT COALESCE(SUM(CASE WHEN accounting_version=2 THEN accounting_amount ELSE amount END),0) AS repository_value FROM compute_deposit WHERE member_id=#{args[0]} AND CASE WHEN accounting_version=2 THEN 'USDT' WHEN currency IN('USD','USDT','USDC') THEN 'USDT' ELSE currency END=#{args[1]} AND status='APPROVED'")
    List<Map<String, Object>> checkEligibilityDepositValue(@Param("args") Object[] args);

    @Select("SELECT COALESCE(SUM(amount),0) AS repository_value FROM compute_order WHERE member_id=#{args[0]} AND CASE WHEN currency IN('USD','USDT','USDC') THEN 'USDT' ELSE currency END=#{args[1]} AND status='ACTIVE'")
    List<Map<String, Object>> checkEligibilityOrderValue(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_activity_participation WHERE activity_id=#{args[0]} AND member_id=#{args[1]} AND created_at>=#{args[2]} AND created_at<#{args[3]}")
    List<Map<String, Object>> todayCountActivityParticipationCount(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_activity_participation WHERE activity_id=#{args[0]} AND member_id=#{args[1]}")
    List<Map<String, Object>> participationCountActivityParticipationCount(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_activity_participation WHERE redemption_code=#{args[0]}")
    List<Map<String, Object>> nextRedemptionCodeActivityParticipationCount(@Param("args") Object[] args);
    @SelectProvider(type=ComputeActivityQueries.class,method="pageCount")
    long pageCount(Map<String,Object> parameters);
    @SelectProvider(type=ComputeActivityQueries.class,method="participationCount")
    long participationCount(Map<String,Object> parameters);
    @SelectProvider(type=ComputeActivityQueries.class,method="participationsCount")
    long participationsCount(Map<String,Object> parameters);
    @SelectProvider(type=ComputeActivityQueries.class,method="pageRows")
    List<Map<String,Object>> pageRows(Map<String,Object> parameters);
    @SelectProvider(type=ComputeActivityQueries.class,method="participationsRows")
    List<Map<String,Object>> participationsRows(Map<String,Object> parameters);
}
