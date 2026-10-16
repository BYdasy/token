package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputePresentationService 的参数化数据库语句。 */
@Mapper
public interface ComputePresentationMapper {

    @Insert("INSERT INTO system_setting(setting_key,setting_value,updated_at,group_name) VALUES(#{args[0]},#{args[1]},#{args[2]},'compute') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),updated_at=VALUES(updated_at),revision=revision+1")
    int saveSettingWrite(@Param("args") Object[] args);

    @Select("SELECT DISTINCT currency AS repository_value FROM compute_currency")
    List<Map<String, Object>> ratesCurrencyRows(@Param("args") Object[] args);

    @Select("SELECT username,level,security_score FROM compute_member WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> memberMemberRow(@Param("args") Object[] args);

    @Select("SELECT wallet_type AS type,currency,available,frozen FROM compute_wallet WHERE member_id=#{args[0]} ORDER BY wallet_type,currency")
    List<Map<String, Object>> memberWalletRows(@Param("args") Object[] args);

    @Select("SELECT id,name,threshold FROM compute_level WHERE enabled=1 AND id>#{args[0]} ORDER BY threshold,id LIMIT 1")
    List<Map<String, Object>> memberLevelRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) total_count,0 active_count,0 active_principal,COALESCE(SUM(paid_income),0) total_income FROM compute_auto_contract WHERE member_id=#{args[0]} AND currency=#{args[1]}")
    List<Map<String, Object>> memberAutoContractCount(@Param("args") Object[] args);

    @Select("SELECT COALESCE(SUM(amount),0) AS repository_value FROM compute_ledger WHERE member_id=#{args[0]} AND currency=#{args[1]} AND kind='AUTO_INCOME' AND created_at>=#{args[2]} AND created_at<#{args[3]}")
    List<Map<String, Object>> memberLedgerValue(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) messages FROM compute_content_recipient r JOIN compute_content c ON c.id=r.content_id WHERE r.member_id=#{args[0]} AND r.read_at IS NULL AND (c.kind='MESSAGE' OR (c.kind='TASK' AND COALESCE(c.task_mode,CASE c.task_type WHEN 'INVESTMENT' THEN 1 WHEN 'WITHDRAWAL' THEN 2 ELSE 4 END) IN (4,5,6))) AND c.enabled=1 AND c.deleted=0 AND c.status='PUBLISHED' AND (c.starts_at IS NULL OR c.starts_at<=#{args[1]}) AND (c.ends_at IS NULL OR c.ends_at>#{args[2]})")
    List<Map<String, Object>> unreadContentRecipientCount(@Param("args") Object[] args);
}
