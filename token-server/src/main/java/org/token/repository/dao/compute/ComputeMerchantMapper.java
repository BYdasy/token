package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeMerchantService 的参数化数据库语句。 */
@Mapper
public interface ComputeMerchantMapper {

    @Select("SELECT resource,last_attempt_at,last_success_at,error_key,snapshot_json FROM compute_merchant_snapshot WHERE merchant_id=#{args[0]} AND environment=#{args[1]} ORDER BY resource")
    List<Map<String, Object>> synchronizationMerchantSnapshotRows(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_merchant_snapshot(merchant_id,environment,resource,last_attempt_at) VALUES(#{args[0]},#{args[1]},#{args[2]},UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE last_attempt_at=VALUES(last_attempt_at)")
    int synchronizeMerchantSnapshotWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_merchant_snapshot SET snapshot_json=#{args[0]},last_success_at=UTC_TIMESTAMP(6),error_key=NULL WHERE merchant_id=#{args[1]} AND environment=#{args[2]} AND resource=#{args[3]}")
    int synchronizeMerchantSnapshotWrite2(@Param("args") Object[] args);

    @Update("UPDATE compute_merchant_snapshot SET error_key=#{args[0]} WHERE merchant_id=#{args[1]} AND environment=#{args[2]} AND resource=#{args[3]}")
    int synchronizeMerchantSnapshotWrite3(@Param("args") Object[] args);
}
