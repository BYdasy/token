package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeJobService 的参数化数据库语句。 */
@Mapper
public interface ComputeJobMapper {

    @Select("SELECT * FROM compute_job ORDER BY id")
    List<Map<String, Object>> jobsJobRows(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_job WHERE schedule_time_zone IS NULL OR schedule_time_zone<>#{args[0]}")
    List<Map<String, Object>> synchronizeScheduleZoneJobRows(@Param("args") Object[] args);

    @Select("SELECT id,cron_expression,schedule_time_zone FROM compute_job WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> synchronizeScheduleZoneJobRow(@Param("args") Object[] args);

    @Update("UPDATE compute_job SET next_run_at=#{args[0]},schedule_time_zone=#{args[1]} WHERE id=#{args[2]}")
    int synchronizeScheduleZoneJobWrite(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_job WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> updateJobRow(@Param("args") Object[] args);

    @Update("UPDATE compute_job SET enabled=#{args[0]},cron_expression=#{args[1]},next_run_at=#{args[2]} WHERE id=#{args[3]}")
    int updateJobWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_job SET schedule_time_zone=#{args[0]} WHERE id=#{args[1]}")
    int updateJobWrite2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_job WHERE id=#{args[0]}")
    List<Map<String, Object>> updateJobRow2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_job WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> submitJobRow(@Param("args") Object[] args);

    @Update("UPDATE compute_job SET active_run_id=#{args[0]},next_run_at=#{args[1]},last_run_at=#{args[2]} WHERE id=#{args[3]}")
    int submitJobWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_job_run WHERE id=#{args[0]}")
    List<Map<String, Object>> submitJobRunRow(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_job_run WHERE status IN ('PENDING','RUNNING') AND created_at<#{args[0]} LIMIT 20")
    List<Map<String, Object>> scheduleJobRunRows(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_job WHERE enabled=1 AND active_run_id IS NULL AND next_run_at<=#{args[0]} ORDER BY next_run_at LIMIT 10")
    List<Map<String, Object>> scheduleJobRows(@Param("args") Object[] args);

    @Update("UPDATE compute_job_run SET status='RUNNING',started_at=#{args[0]} WHERE id=#{args[1]} AND status='PENDING'")
    int executeJobRunWrite(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_order WHERE status='ACTIVE' AND id>#{args[0]} AND starts_at<=#{args[1]} ORDER BY id LIMIT 200")
    List<Map<String, Object>> executeOrderRows(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id>#{args[0]} ORDER BY id LIMIT 200")
    List<Map<String, Object>> executeMemberRows(@Param("args") Object[] args);


    @Select("SELECT * FROM compute_model WHERE enabled=1 ORDER BY id")
    List<Map<String, Object>> refreshModelsModelRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_model WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> refreshModelsModelRow(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_model_price(model_id,price_date,input_price,output_price,change_percent,created_at) VALUES(#{args[0]},#{args[1]},#{args[2]},#{args[3]},#{args[4]},#{args[5]}) ON DUPLICATE KEY UPDATE id=id")
    int refreshModelsModelPriceWrite(@Param("args") Object[] args);

    @Select("SELECT input_price,output_price FROM compute_model_price WHERE model_id=#{args[0]} AND price_date=#{args[1]}")
    List<Map<String, Object>> refreshModelsModelPriceRow(@Param("args") Object[] args);

    @Update("UPDATE compute_model SET current_input_price=#{args[0]},current_output_price=#{args[1]},updated_at=#{args[2]} WHERE id=#{args[3]}")
    int refreshModelsModelWrite(@Param("args") Object[] args);





    @Update("UPDATE compute_job_run SET processed_count=#{args[0]} WHERE id=#{args[1]} AND status='RUNNING'")
    int progressJobRunWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_job_run WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> finishJobRunRow(@Param("args") Object[] args);

    @Update("UPDATE compute_job_run SET status=#{args[0]},processed_count=#{args[1]},error_key=#{args[2]},finished_at=#{args[3]} WHERE id=#{args[4]} AND status IN ('PENDING','RUNNING')")
    int finishJobRunWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_job SET active_run_id=NULL WHERE id=#{args[0]} AND active_run_id=#{args[1]}")
    int finishJobWrite(@Param("args") Object[] args);
}
