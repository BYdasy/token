package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeContentTranslationService 的参数化数据库语句。 */
@Mapper
public interface ComputeContentTranslationMapper {

    @SelectProvider(type=ComputeContentQuerySql.class,method="select")
    List<Map<String,Object>> scopedContentRows(Map<String,Object> parameters);

    @SelectProvider(type=ComputeTranslationResourceSql.class,method="select")
    List<Map<String,Object>> resourceRows(Map<String,Object> parameters);

    @UpdateProvider(type=ComputeTranslationResourceSql.class,method="update")
    int saveResourceTranslations(Map<String,Object> parameters);

    @Select("SELECT * FROM compute_translation_form WHERE requested_by=#{args[0]} AND resource_type=#{args[1]} AND request_hash=#{args[2]} FOR UPDATE")
    List<Map<String, Object>> submitFormTranslationFormRows(@Param("args") Object[] args);

    @Select("SELECT f.* FROM compute_content_translation_task t JOIN compute_translation_form f ON f.id=t.content_id WHERE t.id=#{args[0]} AND t.resource_type='FORM' AND f.resource_type='CONTENT' AND f.requested_by=#{args[1]}")
    List<Map<String, Object>> formContentKindContentTranslationTaskRows(@Param("args") Object[] args);

    @Select("SELECT f.* FROM compute_content_translation_task t JOIN compute_translation_form f ON f.id=t.content_id WHERE t.id=#{args[0]} AND t.resource_type='FORM' AND f.resource_type='ACTIVITY' AND f.requested_by=#{args[1]}")
    List<Map<String, Object>> formActivityTypeContentTranslationTaskRows(@Param("args") Object[] args);

    @Select("SELECT t.* FROM compute_content_translation_task t JOIN compute_translation_form f ON f.id=t.content_id WHERE t.id=#{args[0]} AND t.resource_type='FORM' AND f.resource_type=#{args[1]} AND f.requested_by=#{args[2]}")
    List<Map<String, Object>> formTaskContentTranslationTaskRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_content_translation_task WHERE id=#{args[0]} AND content_id=#{args[1]} AND resource_type=#{args[2]}")
    List<Map<String, Object>> resourceTaskContentTranslationTaskRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_content_translation_item WHERE task_id=#{args[0]} AND language=#{args[1]}")
    List<Map<String, Object>> applyResourceTranslationContentTranslationItemRow(@Param("args") Object[] args);

    @Update("UPDATE compute_content_translation_item SET status='SUPERSEDED',run_token=NULL,lease_until=NULL,error_key=#{args[0]},updated_at=#{args[1]} WHERE task_id IN (SELECT id FROM compute_content_translation_task WHERE content_id=#{args[2]} AND content_version<>#{args[3]} AND resource_type=#{args[4]})")
    int supersedeResourceContentTranslationItemWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_content_translation_task SET status='SUPERSEDED',updated_at=#{args[0]} WHERE content_id=#{args[1]} AND content_version<>#{args[2]} AND resource_type=#{args[3]}")
    int supersedeResourceContentTranslationTaskWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_content_translation_task WHERE id=#{args[0]}")
    List<Map<String, Object>> ensureEnglishContentTranslationTaskRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_content_translation_task WHERE content_id=#{args[0]} AND content_version=#{args[1]} AND resource_type=#{args[2]} FOR UPDATE")
    List<Map<String, Object>> enqueueContentTranslationTaskRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_content_translation_item WHERE task_id=#{args[0]} AND language=#{args[1]} FOR UPDATE")
    List<Map<String, Object>> enqueueContentTranslationItemRows(@Param("args") Object[] args);

    @Update("UPDATE compute_content_translation_item SET status='SUCCEEDED',run_token=NULL,lease_until=NULL,error_key=NULL,retry_after=NULL,updated_at=#{args[0]} WHERE id=#{args[1]}")
    int enqueueContentTranslationItemWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_content_translation_item SET status='PENDING',run_token=NULL,lease_until=NULL,error_key=NULL,retry_after=NULL,updated_at=#{args[0]} WHERE id=#{args[1]}")
    int enqueueContentTranslationItemWrite2(@Param("args") Object[] args);

    @Select("SELECT id,task_id FROM compute_content_translation_item WHERE status='PENDING' ORDER BY CASE WHEN language='en' THEN 0 ELSE 1 END,updated_at,id LIMIT 8")
    List<Map<String, Object>> dispatchPendingContentTranslationItemRows(@Param("args") Object[] args);

    @Update("UPDATE compute_content_translation_item SET status='QUEUED',run_token=#{args[0]},lease_until=#{args[1]},updated_at=#{args[2]} WHERE id=#{args[3]} AND status='PENDING'")
    int dispatchPendingContentTranslationItemWrite(@Param("args") Object[] args);

    @Select("SELECT id,run_token FROM compute_content_translation_item WHERE status IN ('QUEUED','RUNNING') AND lease_until<#{args[0]} LIMIT 100")
    List<Map<String, Object>> recoverLeasesContentTranslationItemRows(@Param("args") Object[] args);

    @Update("UPDATE compute_content_translation_item SET status='RUNNING',attempts=attempts+1,lease_until=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]} AND status='QUEUED' AND run_token=#{args[3]} AND lease_until>=#{args[4]}")
    int translateItemContentTranslationItemWrite(@Param("args") Object[] args);

    @Select("SELECT i.*,t.content_id,t.content_version,t.resource_type,t.source_title,t.source_body FROM compute_content_translation_item i JOIN compute_content_translation_task t ON t.id=i.task_id WHERE i.id=#{args[0]}")
    List<Map<String, Object>> translateItemContentTranslationItemRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_content_translation_item WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> finishContentTranslationItemRow(@Param("args") Object[] args);

    @Update("UPDATE compute_content_translation_item SET status='SUPERSEDED',run_token=NULL,lease_until=NULL,error_key=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]}")
    int finishContentTranslationItemWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_company_file SET names_json=#{args[0]} WHERE id=#{args[1]}")
    int finishCompanyFileWrite(@Param("args") Object[] args);

    @Select("SELECT task_id,attempts FROM compute_content_translation_item WHERE id=#{args[0]}")
    List<Map<String, Object>> failContentTranslationItemRows(@Param("args") Object[] args);

    @Update("UPDATE compute_content_translation_item SET status='FAILED',run_token=NULL,lease_until=NULL,retry_after=#{args[0]},error_key=#{args[1]},updated_at=#{args[2]} WHERE id=#{args[3]} AND run_token=#{args[4]} AND status IN ('QUEUED','RUNNING')")
    int failContentTranslationItemWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_content_translation_task SET status=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]} AND status<>'SUPERSEDED'")
    int refreshTaskContentTranslationTaskWrite(@Param("args") Object[] args);

    @Select("SELECT language,status,attempts,error_key,retry_after FROM compute_content_translation_item WHERE task_id=#{args[0]} ORDER BY language")
    List<Map<String, Object>> taskViewContentTranslationItemRows(@Param("args") Object[] args);
}
