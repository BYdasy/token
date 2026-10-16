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
}
