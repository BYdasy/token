package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeFinanceSnapshotService 的参数化数据库语句。 */
@Mapper
public interface ComputeFinanceSnapshotMapper {

    @Select("SELECT business_id FROM compute_finance_snapshot WHERE business_type=#{args[0]} AND business_id=#{args[1]}")
    List<Map<String, Object>> captureFinanceSnapshotRows(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_finance_snapshot(business_type,business_id,order_no,member_id,currency,\n  native_amount,native_fee,native_net_amount,usd_rate,usd_amount,usd_fee,usd_net_amount,\n  rate_provider,rate_source_at,rate_refreshed_at,snapshot_source,posted_at,captured_at)\nVALUES(#{args[0]},#{args[1]},#{args[2]},#{args[3]},#{args[4]},#{args[5]},#{args[6]},#{args[7]},#{args[8]},#{args[9]},#{args[10]},#{args[11]},#{args[12]},#{args[13]},#{args[14]},#{args[15]},#{args[16]},#{args[17]})\n")
    int captureFinanceSnapshotWrite(@Param("args") Object[] args);
}
