package org.token.repository.dao.compute;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** VIP 资格、周期与等级展示的数据库访问；事务和升级判定留在业务服务。 */
@Mapper
public interface ComputeVipMapper {
    @Select("SELECT available FROM compute_wallet WHERE member_id=#{memberId} AND wallet_type='COMPUTE' AND currency=#{currency}")
    List<Map<String,Object>> availableBalance(@Param("memberId") long memberId,@Param("currency") String currency);

    @Select("""
            SELECT l.id AS level_id,l.threshold,l.required_direct_members,l.direct_member_deposit_threshold,
                   COUNT(q.member_id) AS qualified_direct_members
            FROM compute_level l
            LEFT JOIN (
                SELECT m.id AS member_id,SUM(s.usd_amount) AS deposit_token
                FROM compute_member m
                JOIN compute_deposit d ON d.member_id=m.id AND d.status='APPROVED'
                JOIN compute_finance_snapshot s ON s.business_type='DEPOSIT' AND s.business_id=d.id
                     AND s.member_id=d.member_id AND s.usd_amount IS NOT NULL
                WHERE m.inviter_id=#{memberId}
                GROUP BY m.id HAVING SUM(s.usd_amount)>0
            ) q ON q.deposit_token>=l.direct_member_deposit_threshold
            WHERE l.enabled=1
            GROUP BY l.id,l.threshold,l.required_direct_members,l.direct_member_deposit_threshold
            ORDER BY l.threshold,l.id
            """)
    List<Map<String,Object>> eligibilityLevels(@Param("memberId") long memberId);

    @Select("SELECT id AS level_id,threshold,required_direct_members,direct_member_deposit_threshold,auto_upgrade_enabled FROM compute_level WHERE enabled=1 ORDER BY id FOR UPDATE")
    List<Map<String,Object>> lockAutomaticLevels();

    @Select("""
            SELECT m.id AS member_id,SUM(s.usd_amount) AS deposit_token
            FROM compute_member m JOIN compute_deposit d ON d.member_id=m.id AND d.status='APPROVED'
            JOIN compute_finance_snapshot s ON s.business_type='DEPOSIT' AND s.business_id=d.id
                 AND s.member_id=d.member_id AND s.usd_amount IS NOT NULL
            WHERE m.inviter_id=#{memberId} GROUP BY m.id HAVING SUM(s.usd_amount)>0
            """)
    List<Map<String,Object>> directDeposits(@Param("memberId") long memberId);

    @Select("SELECT id,level,vip_started_at,vip_expires_at FROM compute_member WHERE id=#{memberId} FOR UPDATE")
    List<Map<String,Object>> lockMember(@Param("memberId") long memberId);
    @Select("SELECT id FROM compute_member WHERE id=#{memberId} FOR UPDATE")
    List<Map<String,Object>> lockMemberId(@Param("memberId") long memberId);
    @Select("SELECT id,level FROM compute_member WHERE id=#{memberId} FOR UPDATE")
    List<Map<String,Object>> lockMemberLevel(@Param("memberId") long memberId);
    @Select("SELECT * FROM compute_level WHERE id=#{levelId}")
    List<Map<String,Object>> level(@Param("levelId") long levelId);
    @Select("SELECT * FROM compute_level WHERE id=#{levelId} AND enabled=1 FOR UPDATE")
    List<Map<String,Object>> enabledLevel(@Param("levelId") long levelId);
    @Select("SELECT setting_value FROM system_setting WHERE setting_key=#{key} FOR UPDATE")
    List<Map<String,Object>> lockSwitch(@Param("key") String key);
    @Update("UPDATE compute_member SET vip_started_at=#{start},vip_expires_at=#{expiry} WHERE id=#{memberId} AND vip_started_at IS NULL")
    int initialize(@Param("memberId") long memberId,@Param("start") LocalDateTime start,@Param("expiry") LocalDateTime expiry);
    @Update("UPDATE compute_member SET level=#{levelId},updated_at=#{now} WHERE id=#{memberId}")
    int keepPeriod(@Param("memberId") long memberId,@Param("levelId") long levelId,@Param("now") LocalDateTime now);
    @Update("UPDATE compute_member SET level=#{levelId},vip_started_at=#{start},vip_expires_at=#{expiry},updated_at=#{start} WHERE id=#{memberId}")
    int resetPeriod(@Param("memberId") long memberId,@Param("levelId") long levelId,@Param("start") LocalDateTime start,@Param("expiry") LocalDateTime expiry);
    @Select("""
            <script>SELECT id,name,description,source_language,translations_json FROM compute_level WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    List<Map<String,Object>> names(@Param("ids") List<Long> ids);
}
