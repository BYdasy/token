package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeMcagChannelService 的参数化数据库语句。 */
@Mapper
public interface ComputeMcagChannelMapper {

    @Select("SELECT * FROM compute_mcag_channel WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND currency=#{args[2]} AND network=#{args[3]}")
    List<Map<String, Object>> findChannelMcagChannelRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_channel WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND asset_id=#{args[2]} AND network_id=#{args[3]}")
    List<Map<String, Object>> findByAssetMcagChannelRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_channel WHERE merchant_id=#{args[0]} AND environment=#{args[1]} ORDER BY currency,network")
    List<Map<String, Object>> listSyncedChannelsMcagChannelRows(@Param("args") Object[] args);
}
