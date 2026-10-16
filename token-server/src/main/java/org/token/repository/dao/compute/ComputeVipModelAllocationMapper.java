package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ComputeVipModelAllocationMapper {

    @Select("<script>SELECT m.id AS model_id,m.name,p.id AS provider_id,p.name AS provider FROM compute_model m JOIN compute_provider p ON p.id=m.provider_id WHERE m.enabled=1 AND p.enabled=1 AND p.id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach> ORDER BY p.id,m.id</script>")
    List<Map<String, Object>> enabledModels(@Param("ids") Collection<Long> ids);
}
