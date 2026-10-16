package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 控制器原有只读查询；参数均绑定，不接收 SQL 片段。 */
@Mapper
public interface ComputeControllerQueryMapper {
    @Select("SELECT type FROM compute_activity WHERE id=#{id}")
    String activityType(@Param("id") long id);

    @Select("SELECT activity_type FROM compute_activity_participation WHERE id=#{id}")
    String participationType(@Param("id") long id);

    @Select("SELECT * FROM compute_address WHERE member_id=#{memberId} AND enabled=1 AND removed=0 ORDER BY id DESC")
    List<Map<String, Object>> memberAddresses(@Param("memberId") long memberId);
}
