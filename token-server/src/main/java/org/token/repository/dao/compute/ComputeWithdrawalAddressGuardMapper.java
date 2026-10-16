package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeWithdrawalAddressGuard 的参数化数据库语句。 */
@Mapper
public interface ComputeWithdrawalAddressGuardMapper {

    @Insert("INSERT INTO compute_address_guard(guard_key,created_at) VALUES(#{args[0]},#{args[1]}) ON DUPLICATE KEY UPDATE guard_key=guard_key")
    int assertAllowedAddressGuardWrite(@Param("args") Object[] args);

    @Select("SELECT guard_key FROM compute_address_guard WHERE guard_key=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> assertAllowedAddressGuardRows(@Param("args") Object[] args);

    @Select("SELECT a.member_id,a.address,a.memo,a.network,a.enabled,a.removed,n.chain_type FROM compute_address a JOIN compute_currency_network n ON n.network_code=a.network WHERE a.member_id<>#{args[0]} AND (a.address_key=#{args[1]} OR (a.address_key IS NULL AND LOWER(a.address)=#{args[2]})) FOR UPDATE")
    List<Map<String, Object>> assertAllowedAddressRows(@Param("args") Object[] args);

    @Select("SELECT a.address,a.network,n.chain_type FROM compute_mcag_address a JOIN compute_currency_network n ON n.network_code=a.network WHERE LOWER(a.address)=#{args[0]}")
    List<Map<String, Object>> assertAllowedMcagAddressRows(@Param("args") Object[] args);

    @Select("SELECT w.address,w.network,n.chain_type FROM compute_withdrawal w JOIN compute_currency_network n ON n.network_code=w.network WHERE w.member_id=#{args[0]} AND w.status IN ('PENDING','PROCESSING') FOR UPDATE")
    List<Map<String, Object>> assertRemovableWithdrawalRows(@Param("args") Object[] args);

    @Select("SELECT chain_type FROM compute_currency_network WHERE network_code=#{args[0]}")
    List<Map<String, Object>> assertRemovableCurrencyNetworkRow(@Param("args") Object[] args);
}
