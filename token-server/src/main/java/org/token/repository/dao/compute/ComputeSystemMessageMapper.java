package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeSystemMessageService 的参数化数据库语句。 */
@Mapper
public interface ComputeSystemMessageMapper {

    @Select("SELECT event_code,enabled FROM compute_system_message_template")
    List<Map<String, Object>> settingsSystemMessageTemplateRows(@Param("args") Object[] args);

    @Select("SELECT event_code FROM compute_system_message_template WHERE event_code=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> updateTranslationKeySystemMessageTemplateRow(@Param("args") Object[] args);

    @Select("SELECT event_code,enabled FROM compute_system_message_template ORDER BY event_code")
    List<Map<String, Object>> templatesSystemMessageTemplateRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM translate_language WHERE LOWER(REPLACE(lang,'_','-'))=#{args[0]} AND status=1")
    List<Map<String, Object>> saveTranslateLanguageCount(@Param("args") Object[] args);

    @Update("UPDATE compute_system_message_template SET enabled=#{args[0]},updated_at=#{args[1]} WHERE event_code=#{args[2]}")
    int saveSystemMessageTemplateWrite(@Param("args") Object[] args);

    @Select("SELECT order_no FROM compute_deposit WHERE id=#{args[0]}")
    List<Map<String, Object>> depositApprovedDepositRow(@Param("args") Object[] args);

    @Select("SELECT order_no FROM compute_withdrawal WHERE id=#{args[0]}")
    List<Map<String, Object>> withdrawalCompletedWithdrawalRow(@Param("args") Object[] args);

    @Select("SELECT enabled FROM compute_system_message_template WHERE event_code=#{args[0]}")
    List<Map<String, Object>> deliverSystemMessageTemplateRows(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_system_message_delivery(event_code,source_id,member_id,created_at) VALUES(#{args[0]},#{args[1]},#{args[2]},#{args[3]})")
    int deliverSystemMessageDeliveryWrite(@Param("args") Object[] args);

    @Select("SELECT COALESCE(username,email,phone,'') username FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> deliverMemberRow(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_content_recipient(content_id,member_id,created_at) VALUES(#{args[0]},#{args[1]},#{args[2]})")
    int deliverContentRecipientWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_system_message_delivery SET content_id=#{args[0]} WHERE event_code=#{args[1]} AND source_id=#{args[2]}")
    int deliverSystemMessageDeliveryWrite2(@Param("args") Object[] args);

    @Select("SELECT lang,tkey,tvalue FROM translates WHERE type=6 AND tkey IN (#{args[0]},#{args[1]})")
    List<Map<String, Object>> translationsTranslatesRows(@Param("args") Object[] args);

    @Select("SELECT id FROM translates WHERE tkey=#{args[0]} AND LOWER(REPLACE(lang,'_','-'))=#{args[1]} AND type=6 LIMIT 1")
    List<Map<String, Object>> upsertTranslatesRows(@Param("args") Object[] args);

    @Insert("INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) VALUES(#{args[0]},#{args[1]},#{args[2]},6,#{args[3]},#{args[4]},0,#{args[5]})")
    int upsertTranslatesWrite(@Param("args") Object[] args);

    @Update("UPDATE translates SET tvalue=#{args[0]},prop=#{args[1]},action_time=#{args[2]} WHERE id=#{args[3]}")
    int upsertTranslatesWrite2(@Param("args") Object[] args);
}
