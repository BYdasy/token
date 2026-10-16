package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeMcagCollectionService 的参数化数据库语句。 */
@Mapper
public interface ComputeMcagCollectionMapper {

    @Select("SELECT * FROM compute_mcag_address WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND customer_id=#{args[2]} AND address_id=#{args[3]} AND asset_id=#{args[4]} AND network_id=#{args[5]}")
    List<Map<String, Object>> applyMcagAddressRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_address WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> applyMcagAddressRow(@Param("args") Object[] args);

    @Select("SELECT id,is_test FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> applyMemberRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_cursor WHERE merchant_id=#{args[0]} AND environment=#{args[1]} FOR UPDATE")
    List<Map<String, Object>> applyMcagCursorRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_collection_batch WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND batch_id=#{args[2]} FOR UPDATE")
    List<Map<String, Object>> applyMcagCollectionBatchRows(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_collection_batch SET resource_version=#{args[0]},member_id=#{args[1]},state=#{args[2]},reason=#{args[3]},sweep_status=#{args[4]},collection_json=#{args[5]},updated_at=#{args[6]} WHERE id=#{args[7]}")
    int applyMcagCollectionBatchWrite(@Param("args") Object[] args);
}
