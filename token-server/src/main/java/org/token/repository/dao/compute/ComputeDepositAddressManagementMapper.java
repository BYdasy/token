package org.token.repository.dao.compute;

import java.util.*;
import org.token.model.query.MemberScope;
import org.token.repository.compute.ComputeDepositAddressManagementRepository;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeDepositAddressManagementService 的参数化数据库语句。 */
@Mapper
public interface ComputeDepositAddressManagementMapper {

    @Select("SELECT id FROM compute_member WHERE username=#{args[0]}")
    List<Map<String, Object>> generateMemberRow(@Param("args") Object[] args);

    @Select("SELECT is_test FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> generateMemberRow2(@Param("args") Object[] args);

    @Select("SELECT network_id,disabled_at FROM compute_mcag_address WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND member_id=#{args[2]} AND wallet_type=#{args[3]}")
    List<Map<String, Object>> generateMcagAddressRows(@Param("args") Object[] args);

    @Select("SELECT member_id,wallet_type,network_id,address_id,address,customer_id FROM compute_mcag_address WHERE id=#{args[0]} AND merchant_id=#{args[1]} AND environment=#{args[2]}")
    List<Map<String, Object>> disableMcagAddressRow(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> disableMemberRow(@Param("args") Object[] args);

    @Select("SELECT member_id,wallet_type,network_id,address_id,address,customer_id FROM compute_mcag_address WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND member_id=#{args[2]} AND wallet_type=#{args[3]} AND LOWER(address)=LOWER(#{args[4]}) AND customer_id=#{args[5]} ORDER BY network_id,id")
    List<Map<String, Object>> disableMcagAddressRows(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_mcag_address_generation(merchant_id,environment,member_id,wallet_type,network_id,generation,updated_at) VALUES(#{args[0]},#{args[1]},#{args[2]},#{args[3]},#{args[4]},0,#{args[5]}) ON DUPLICATE KEY UPDATE member_id=member_id")
    int disableNetworkMcagAddressGenerationWrite(@Param("args") Object[] args);

    @Select("SELECT generation FROM compute_mcag_address_generation WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND member_id=#{args[2]} AND wallet_type=#{args[3]} AND network_id=#{args[4]} FOR UPDATE")
    List<Map<String, Object>> disableNetworkMcagAddressGenerationRow(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_mcag_address_current WHERE binding_id=#{args[0]}")
    List<Map<String, Object>> disableNetworkMcagAddressCurrentCount(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_mcag_address_current c JOIN compute_mcag_address a ON a.id=c.binding_id WHERE a.merchant_id=#{args[0]} AND a.environment=#{args[1]} AND a.member_id=#{args[2]} AND a.wallet_type=#{args[3]} AND a.network_id=#{args[4]} AND a.customer_id=#{args[5]} AND a.disabled_at IS NULL")
    List<Map<String, Object>> disableNetworkMcagAddressCurrentCount2(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_address_current SET address_status='DISABLED',monitoring_ready=0,verified_at=NULL,lease_token=NULL,lease_until=NULL WHERE binding_id=#{args[0]}")
    int disableNetworkMcagAddressCurrentWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_address_generation SET generation=#{args[0]},updated_at=#{args[1]} WHERE merchant_id=#{args[2]} AND environment=#{args[3]} AND member_id=#{args[4]} AND wallet_type=#{args[5]} AND network_id=#{args[6]}")
    int disableNetworkMcagAddressGenerationWrite2(@Param("args") Object[] args);

    @Select("SELECT network_code FROM compute_currency_network WHERE chain_type='EVM'")
    List<Map<String, Object>> evmNetworksCurrencyNetworkRows(@Param("args") Object[] args);

    @SelectProvider(type=ComputeDepositAddressManagementRepository.Queries.class,method="page")
    List<Map<String,Object>> page(Map<String,Object> parameters);

    @SelectProvider(type=ComputeDepositAddressManagementRepository.Queries.class,method="filterOptions")
    List<Map<String,Object>> filterOptions(Map<String,Object> parameters);
}
