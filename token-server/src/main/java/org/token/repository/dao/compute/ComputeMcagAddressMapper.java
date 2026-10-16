package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ComputeMcagAddressMapper {

    @Select("<script>SELECT id FROM compute_mcag_address WHERE merchant_id=#{identity.merchant} AND environment=#{identity.environment} AND member_id=#{identity.memberId} AND wallet_type=#{identity.wallet} AND network_id=#{identity.networkId} AND (address_id=#{identity.addressId} OR <choose><when test='evm'>LOWER(address)=LOWER(#{identity.address})</when><otherwise>address=#{identity.address}</otherwise></choose>) AND disabled_at IS NOT NULL</script>")
    List<Map<String, Object>> disabledIds(@Param("identity") org.token.model.query.DepositAddressIdentity identity, @Param("evm") boolean evm);

    @Select("<script>SELECT disabled_at,disabled_by FROM compute_mcag_address WHERE merchant_id=#{identity.merchant} AND environment=#{identity.environment} AND member_id=#{identity.memberId} AND wallet_type=#{identity.wallet} AND network_id=#{identity.networkId} AND (address_id=#{identity.addressId} OR <choose><when test='evm'>LOWER(address)=LOWER(#{identity.address})</when><otherwise>address=#{identity.address}</otherwise></choose>) AND disabled_at IS NOT NULL ORDER BY disabled_at LIMIT 1</script>")
    List<Map<String, Object>> disabledOrigin(@Param("identity") org.token.model.query.DepositAddressIdentity identity, @Param("evm") boolean evm);

    @Select("<script>SELECT id FROM compute_mcag_address WHERE merchant_id=#{identity.merchant} AND environment=#{identity.environment} AND member_id=#{identity.memberId} AND wallet_type=#{identity.wallet} AND network_id=#{identity.networkId} AND (address_id=#{identity.addressId} OR <choose><when test='evm'>LOWER(address)=LOWER(#{identity.address})</when><otherwise>address=#{identity.address}</otherwise></choose>) FOR UPDATE</script>")
    List<Map<String, Object>> lockBindings(@Param("identity") org.token.model.query.DepositAddressIdentity identity, @Param("evm") boolean evm);

    @Update("<script>UPDATE compute_mcag_address SET disabled_at=COALESCE(disabled_at,#{time}),disabled_by=COALESCE(disabled_by,#{operator}) WHERE merchant_id=#{identity.merchant} AND environment=#{identity.environment} AND member_id=#{identity.memberId} AND wallet_type=#{identity.wallet} AND network_id=#{identity.networkId} AND (address_id=#{identity.addressId} OR <choose><when test='evm'>LOWER(address)=LOWER(#{identity.address})</when><otherwise>address=#{identity.address}</otherwise></choose>)</script>")
    int disableBindings(@Param("identity") org.token.model.query.DepositAddressIdentity identity, @Param("evm") boolean evm, @Param("time") LocalDateTime time, @Param("operator") int operator);
}
