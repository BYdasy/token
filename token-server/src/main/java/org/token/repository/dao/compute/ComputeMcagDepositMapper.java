package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeMcagDepositService 的参数化数据库语句。 */
@Mapper
public interface ComputeMcagDepositMapper {

    @Select("SELECT id,is_test FROM compute_member WHERE id=#{args[0]} AND enabled=1 FOR UPDATE")
    List<Map<String, Object>> bindMemberRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_address_current WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND customer_id=#{args[2]} AND asset_id=#{args[3]} FOR UPDATE")
    List<Map<String, Object>> bindMcagAddressCurrentRow(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_address_current SET binding_id=#{args[0]},mapping_fingerprint=#{args[1]},address_status=#{args[2]},monitoring_ready=#{args[3]},verified_at=#{args[4]},lease_token=NULL,lease_until=NULL WHERE id=#{args[5]}")
    int bindMcagAddressCurrentWrite(@Param("args") Object[] args);

    @Select("SELECT a.*,c.address_status,c.monitoring_ready,c.verified_at FROM compute_mcag_address_current c JOIN compute_mcag_address a ON a.id=c.binding_id WHERE c.id=#{args[0]}")
    List<Map<String, Object>> bindMcagAddressCurrentRow2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_address WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND address_id=#{args[2]} AND asset_id=#{args[3]} FOR UPDATE")
    List<Map<String, Object>> rememberBindingMcagAddressRows(@Param("args") Object[] args);

    @Select("SELECT a.*,c.address_status,c.monitoring_ready,c.verified_at FROM compute_mcag_address_current c JOIN compute_mcag_address a ON a.id=c.binding_id WHERE c.merchant_id=#{args[0]} AND c.environment=#{args[1]} AND c.customer_id=#{args[2]} AND c.asset_id=#{args[3]} AND c.mapping_fingerprint=#{args[4]} AND a.member_id=#{args[5]} AND a.wallet_type=#{args[6]} AND a.disabled_at IS NULL")
    List<Map<String, Object>> cachedMcagAddressCurrentRows(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} AND enabled=1 FOR UPDATE")
    List<Map<String, Object>> claimRefreshMemberRow(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_address_current SET lease_token=#{args[0]},lease_until=#{args[1]} WHERE merchant_id=#{args[2]} AND environment=#{args[3]} AND customer_id=#{args[4]} AND asset_id=#{args[5]} AND (lease_until IS NULL OR lease_until<#{args[6]})")
    int claimRefreshMcagAddressCurrentWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_address_current SET verified_at=NULL,address_status='UNAVAILABLE',monitoring_ready=0,lease_token=NULL,lease_until=NULL WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND customer_id=#{args[2]} AND asset_id=#{args[3]} AND lease_token=#{args[4]}")
    int failRefreshMcagAddressCurrentWrite(@Param("args") Object[] args);

    @Select("SELECT generation FROM compute_mcag_address_generation WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND member_id=#{args[2]} AND wallet_type=#{args[3]} AND network_id=#{args[4]}")
    List<Map<String, Object>> currentCustomerMcagAddressGenerationRows(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_mcag_address_generation(merchant_id,environment,member_id,wallet_type,network_id,generation,updated_at) VALUES(#{args[0]},#{args[1]},#{args[2]},#{args[3]},#{args[4]},0,#{args[5]}) ON DUPLICATE KEY UPDATE member_id=member_id")
    int prepareIdentityMcagAddressGenerationWrite(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_mcag_customer_identity(merchant_id,environment,customer_id,member_id,wallet_type,created_at) VALUES(#{args[0]},#{args[1]},#{args[2]},#{args[3]},#{args[4]},#{args[5]}) ON DUPLICATE KEY UPDATE member_id=member_id")
    int prepareIdentityMcagCustomerIdentityWrite(@Param("args") Object[] args);

    @Select("SELECT member_id,wallet_type FROM compute_mcag_customer_identity WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND customer_id=#{args[2]}")
    List<Map<String, Object>> customerIdentityMcagCustomerIdentityRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) FROM compute_member WHERE id=#{memberId}")
    long registeredMemberCount(@Param("memberId") long memberId);

    @Insert("INSERT INTO compute_mcag_address_current(merchant_id,environment,customer_id,asset_id) VALUES(#{args[0]},#{args[1]},#{args[2]},#{args[3]}) ON DUPLICATE KEY UPDATE id=id")
    int ensureCurrentMcagAddressCurrentWrite(@Param("args") Object[] args);

    @Select("SELECT event_sequence,resource_id,resource_version,event_type,resource_type FROM compute_mcag_event WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND event_id=#{args[2]}")
    List<Map<String, Object>> recordEventMcagEventRows(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_mcag_event(merchant_id,environment,event_id,event_sequence,resource_id,resource_version,state,received_at,event_type,resource_type) VALUES(#{args[0]},#{args[1]},#{args[2]},#{args[3]},#{args[4]},#{args[5]},#{args[6]},#{args[7]},#{args[8]},#{args[9]}) ON DUPLICATE KEY UPDATE event_id=event_id")
    int recordEventMcagEventWrite(@Param("args") Object[] args);

    @Select("SELECT event_id,resource_id,resource_version FROM compute_mcag_event WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND event_sequence=#{args[2]}")
    List<Map<String, Object>> recordEventMcagEventRow(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_mcag_cursor(merchant_id,environment,after_sequence,updated_at) VALUES(#{args[0]},#{args[1]},0,#{args[2]}) ON DUPLICATE KEY UPDATE merchant_id=merchant_id")
    int ensureCursorMcagCursorWrite(@Param("args") Object[] args);

    @Select("SELECT after_sequence AS repository_value FROM compute_mcag_cursor WHERE merchant_id=#{args[0]} AND environment=#{args[1]}")
    List<Map<String, Object>> cursorMcagCursorValue(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_cursor SET after_sequence=#{args[0]},updated_at=#{args[1]} WHERE merchant_id=#{args[2]} AND environment=#{args[3]} AND after_sequence=#{args[4]}")
    int advanceMcagCursorWrite(@Param("args") Object[] args);
}
