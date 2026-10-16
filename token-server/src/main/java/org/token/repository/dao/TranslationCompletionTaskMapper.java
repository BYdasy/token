package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.token.model.entity.TranslationCompletionTask;

/** 翻译模板补齐任务持久层。 */
@Mapper
public interface TranslationCompletionTaskMapper extends BaseMapper<TranslationCompletionTask> {

    @Select("SELECT * FROM translation_completion_task WHERE id=#{id} AND requested_by=#{uid} LIMIT 1")
    org.token.model.entity.TranslationCompletionTask findOwned(@Param("id") Long id,@Param("uid") Integer uid);
    @Select("SELECT * FROM translation_completion_task WHERE status IN ('PENDING','RUNNING')")
    List<org.token.model.entity.TranslationCompletionTask> findActive();
    @Select("SELECT * FROM translation_completion_task WHERE template_type=#{type} AND status IN ('PENDING','RUNNING') ORDER BY id DESC LIMIT 1")
    org.token.model.entity.TranslationCompletionTask findActiveByType(Integer type);
    @Update("UPDATE translation_completion_task SET status='RUNNING',started_at=#{now},updated_at=#{now} WHERE id=#{id} AND status='PENDING'")
    int start(@Param("id") Long id,@Param("now") java.time.LocalDateTime now);
    @Update("""
        UPDATE translation_completion_task SET progress_percent=#{percent},processed_keys=#{progress.processedKeys},total_keys=#{progress.totalKeys},
        inserted=#{progress.inserted},translated_keys=#{progress.translatedKeys},missing_before=#{progress.missingBefore},updated_at=#{now}
        WHERE id=#{id} AND status='RUNNING'
        """)
    int progress(@Param("id") Long id,@Param("percent") int percent,@Param("progress") org.token.model.response.TranslationCompletionProgress progress,@Param("now") java.time.LocalDateTime now);
    @Update("""
        UPDATE translation_completion_task SET status='SUCCEEDED',progress_percent=100,processed_keys=#{result.keyCount},total_keys=#{result.keyCount},
        inserted=#{result.inserted},translated_keys=#{result.translatedKeys},missing_before=#{result.missingBefore},missing_after=#{result.missingAfter},
        error_message=NULL,finished_at=#{now},updated_at=#{now} WHERE id=#{id} AND status='RUNNING'
        """)
    int complete(@Param("id") Long id,@Param("result") org.token.model.response.TranslationCompletionResult result,@Param("now") java.time.LocalDateTime now);
    @Update("UPDATE translation_completion_task SET status='FAILED',error_message=#{error},finished_at=#{now},updated_at=#{now} WHERE id=#{id} AND status IN ('PENDING','RUNNING')")
    int fail(@Param("id") Long id,@Param("error") String error,@Param("now") java.time.LocalDateTime now);

}
