package org.token.repository.dao.compute;

import java.util.*;
import org.token.repository.compute.ComputeCatalogRepository;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeCatalogService 的参数化数据库语句。 */
@Mapper
public interface ComputeCatalogMapper {
    @Select("SELECT id,code,name,image_url FROM compute_provider WHERE enabled=1 AND image_url<>'' ORDER BY id LIMIT 60")
    List<Map<String,Object>> homePartners();

    @Select("SELECT id,name,auto_upgrade_enabled FROM compute_level ORDER BY id")
    List<Map<String,Object>> vipSettings();

    @Select("SELECT * FROM compute_level WHERE enabled=1 ORDER BY threshold,id")
    List<Map<String, Object>> catalogLevelRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_product WHERE enabled=1 AND type='GPU' ORDER BY id")
    List<Map<String, Object>> catalogProductRows(@Param("args") Object[] args);

    @Select("SELECT m.*,p.name AS provider,'USD' AS price_currency FROM compute_model m JOIN compute_provider p ON p.id=m.provider_id WHERE m.enabled=1 AND p.enabled=1 ORDER BY m.id LIMIT #{args[0]}")
    List<Map<String, Object>> publicModelsModelRows(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_provider WHERE id=#{args[0]}")
    List<Map<String, Object>> saveProviderRows(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE username=#{args[0]}")
    List<Map<String, Object>> saveMemberRow(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_level WHERE id=#{args[0]}")
    List<Map<String, Object>> saveLevelCount(@Param("args") Object[] args);

    @Select("SELECT currency,network FROM compute_currency WHERE id=#{args[0]}")
    List<Map<String, Object>> saveCurrencyRow(@Param("args") Object[] args);

    @Select("SELECT id,name FROM compute_provider WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> requireProviderProviderRows(@Param("args") Object[] args);

    @Select("SELECT setting_key,setting_value FROM system_setting WHERE group_name='compute'")
    List<Map<String, Object>> settingsSettingRows(@Param("args") Object[] args);

    @Select("SELECT setting_value AS configuration_json,revision FROM system_setting WHERE setting_key='compute.reward.configuration'")
    List<Map<String, Object>> settingsRewardConfigurationCount(@Param("args") Object[] args);

    @Select("SELECT setting_key,setting_value FROM system_setting WHERE group_name='compute' ORDER BY setting_key FOR UPDATE")
    List<Map<String, Object>> saveSettingsSettingRows(@Param("args") Object[] args);

    @Select("SELECT reward_rules_json FROM compute_level")
    List<Map<String, Object>> saveSettingsLevelRows(@Param("args") Object[] args);

    @Insert("INSERT INTO system_setting(setting_key,setting_value,updated_at,group_name) VALUES(#{args[0]},#{args[1]},#{args[2]},'compute') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),updated_at=VALUES(updated_at),revision=revision+1")
    int saveSettingsSettingWrite(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) member_count FROM compute_member")
    List<Map<String, Object>> overviewMemberCount(@Param("args") Object[] args);

    @Select("SELECT COUNT(CASE WHEN status='ACTIVE' THEN 1 END) active_orders,COALESCE(SUM(CASE WHEN member_id IN (SELECT id FROM compute_member WHERE is_test=0) AND status='ACTIVE' AND currency=#{args[0]} THEN amount ELSE 0 END),0) active_principal,COALESCE(SUM(CASE WHEN member_id IN (SELECT id FROM compute_member WHERE is_test=0) AND currency=#{args[1]} THEN paid_income ELSE 0 END),0) total_income FROM compute_order")
    List<Map<String, Object>> overviewMemberCount2(@Param("args") Object[] args);

    @Select("SELECT COUNT(CASE WHEN status='PENDING' THEN 1 END) pending_deposits,COALESCE(SUM(CASE WHEN member_id IN (SELECT id FROM compute_member WHERE is_test=0) AND status='APPROVED' AND (accounting_version=2 OR currency=#{args[0]}) THEN CASE WHEN accounting_version=2 THEN accounting_amount ELSE amount END ELSE 0 END),0) total_deposits FROM compute_deposit")
    List<Map<String, Object>> overviewMemberCount3(@Param("args") Object[] args);

    @Select("SELECT COUNT(CASE WHEN status='PENDING' THEN 1 END) pending_withdrawals,COALESCE(SUM(CASE WHEN member_id IN (SELECT id FROM compute_member WHERE is_test=0) AND status='APPROVED' AND (accounting_version=2 OR currency=#{args[0]}) THEN CASE WHEN accounting_version=2 THEN accounting_net_amount ELSE net_amount END ELSE 0 END),0) total_withdrawals FROM compute_withdrawal")
    List<Map<String, Object>> overviewMemberCount4(@Param("args") Object[] args);

    @Select("SELECT c.currency,COALESCE(o.active_principal,0) active_principal,COALESCE(o.total_income,0) total_income,\n  COALESCE(d.total_deposits,0) total_deposits,COALESCE(w.total_withdrawals,0) total_withdrawals\nFROM (SELECT DISTINCT currency FROM compute_currency UNION SELECT 'USDT') c\nLEFT JOIN (SELECT currency,SUM(CASE WHEN status='ACTIVE' THEN amount ELSE 0 END) active_principal,SUM(paid_income) total_income\n  FROM compute_order WHERE member_id IN (SELECT id FROM compute_member WHERE is_test=0) GROUP BY currency) o ON o.currency=c.currency\nLEFT JOIN (SELECT CASE WHEN accounting_version=2 THEN 'USDT' ELSE currency END currency,\n  SUM(CASE WHEN accounting_version=2 THEN accounting_amount ELSE amount END) total_deposits\n  FROM compute_deposit WHERE status='APPROVED' AND member_id IN (SELECT id FROM compute_member WHERE is_test=0)\n  GROUP BY CASE WHEN accounting_version=2 THEN 'USDT' ELSE currency END) d ON d.currency=c.currency\nLEFT JOIN (SELECT CASE WHEN accounting_version=2 THEN 'USDT' ELSE currency END currency,\n  SUM(CASE WHEN accounting_version=2 THEN accounting_net_amount ELSE net_amount END) total_withdrawals\n  FROM compute_withdrawal WHERE status='APPROVED' AND member_id IN (SELECT id FROM compute_member WHERE is_test=0)\n  GROUP BY CASE WHEN accounting_version=2 THEN 'USDT' ELSE currency END) w ON w.currency=c.currency\nORDER BY c.currency\n")
    List<Map<String, Object>> overviewCurrencyRows(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> updateMemberMemberRow(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_level WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> updateMemberLevelCount(@Param("args") Object[] args);

    @Select("SELECT id,username,email,phone,level,enabled,invite_code,created_at FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> updateMemberMemberRow2(@Param("args") Object[] args);

    @SelectProvider(type=ComputeCatalogRepository.Queries.class,method="list")
    List<Map<String,Object>> list(@Param("resource") String resource);
    @SelectProvider(type=ComputeCatalogRepository.Queries.class,method="page")
    List<Map<String,Object>> page(Map<String,Object> parameters);
}
