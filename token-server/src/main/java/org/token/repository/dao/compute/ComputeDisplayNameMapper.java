package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ComputeDisplayNameMapper {

    @Select("<script>SELECT uid display_id,username FROM ad_master WHERE uid IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    List<Map<String, Object>> administratorNames(@Param("ids") Collection<Long> ids);

    @Select("<script>SELECT id display_id,username FROM compute_member WHERE id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>")
    List<Map<String, Object>> memberNames(@Param("ids") Collection<Long> ids);

    @Select("<script>SELECT i.customer_id,m.username FROM compute_mcag_customer_identity i "
            + "JOIN compute_member m ON m.id=i.member_id "
            + "WHERE i.merchant_id=#{merchantId} AND i.environment=#{environment} AND i.customer_id IN "
            + "<foreach collection='customerIds' item='customerId' open='(' close=')' separator=','>#{customerId}</foreach></script>")
    List<Map<String, Object>> merchantCustomerNames(@Param("merchantId") String merchantId,
            @Param("environment") String environment, @Param("customerIds") Collection<String> customerIds);
}
