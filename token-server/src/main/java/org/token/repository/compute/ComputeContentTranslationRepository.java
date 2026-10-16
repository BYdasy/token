package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeContentTranslationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeContentTranslationService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeContentTranslationRepository {
    private final ComputeContentTranslationMapper mapper;

    @Autowired
    public ComputeContentTranslationRepository(ComputeContentTranslationMapper mapper) { this.mapper = mapper; }

    public ComputeContentTranslationRepository(ComputeData data) {
        this(data.mapper(ComputeContentTranslationMapper.class));
    }

    public List<Map<String,Object>> scopedContentRows(long id,boolean lock,org.token.model.query.MemberScope scope) {
        return ComputeData.normalizeRows(mapper.scopedContentRows(ComputeData.fields("mode","ADMIN_ROW","id",id,"lock",lock,"scope",scope)));
    }

    public Map<String,Object> resourceRow(String resource,long id,boolean lock) {
        return RepositoryResults.required(mapper.resourceRows(ComputeData.fields("resource",resource,"id",id,"lock",lock)));
    }

    public int saveResourceTranslations(String resource,long id,String translations) {
        return mapper.saveResourceTranslations(ComputeData.fields("resource",resource,"id",id,"translations",translations));
    }

    public List<Map<String, Object>> submitFormTranslationFormRows(Object... args) {
        return ComputeData.normalizeRows(mapper.submitFormTranslationFormRows(args));
    }

    public List<Map<String, Object>> formContentKindContentTranslationTaskRows(Object... args) {
        return ComputeData.normalizeRows(mapper.formContentKindContentTranslationTaskRows(args));
    }

    public List<Map<String, Object>> formActivityTypeContentTranslationTaskRows(Object... args) {
        return ComputeData.normalizeRows(mapper.formActivityTypeContentTranslationTaskRows(args));
    }

    public List<Map<String, Object>> formTaskContentTranslationTaskRows(Object... args) {
        return ComputeData.normalizeRows(mapper.formTaskContentTranslationTaskRows(args));
    }

    public List<Map<String, Object>> resourceTaskContentTranslationTaskRows(Object... args) {
        return ComputeData.normalizeRows(mapper.resourceTaskContentTranslationTaskRows(args));
    }

    public Map<String, Object> applyResourceTranslationContentTranslationItemRow(Object... args) {
        return RepositoryResults.required(mapper.applyResourceTranslationContentTranslationItemRow(args));
    }

    public int supersedeResourceContentTranslationItemWrite(Object... args) {
        return mapper.supersedeResourceContentTranslationItemWrite(args);
    }

    public int supersedeResourceContentTranslationTaskWrite(Object... args) {
        return mapper.supersedeResourceContentTranslationTaskWrite(args);
    }

    public Map<String, Object> ensureEnglishContentTranslationTaskRow(Object... args) {
        return RepositoryResults.required(mapper.ensureEnglishContentTranslationTaskRow(args));
    }

    public List<Map<String, Object>> enqueueContentTranslationTaskRows(Object... args) {
        return ComputeData.normalizeRows(mapper.enqueueContentTranslationTaskRows(args));
    }

    public List<Map<String, Object>> enqueueContentTranslationItemRows(Object... args) {
        return ComputeData.normalizeRows(mapper.enqueueContentTranslationItemRows(args));
    }

    public int enqueueContentTranslationItemWrite(Object... args) {
        return mapper.enqueueContentTranslationItemWrite(args);
    }

    public int enqueueContentTranslationItemWrite2(Object... args) {
        return mapper.enqueueContentTranslationItemWrite2(args);
    }

    public List<Map<String, Object>> dispatchPendingContentTranslationItemRows(Object... args) {
        return ComputeData.normalizeRows(mapper.dispatchPendingContentTranslationItemRows(args));
    }

    public int dispatchPendingContentTranslationItemWrite(Object... args) {
        return mapper.dispatchPendingContentTranslationItemWrite(args);
    }

    public List<Map<String, Object>> recoverLeasesContentTranslationItemRows(Object... args) {
        return ComputeData.normalizeRows(mapper.recoverLeasesContentTranslationItemRows(args));
    }

    public int translateItemContentTranslationItemWrite(Object... args) {
        return mapper.translateItemContentTranslationItemWrite(args);
    }

    public Map<String, Object> translateItemContentTranslationItemRow(Object... args) {
        return RepositoryResults.required(mapper.translateItemContentTranslationItemRow(args));
    }

    public Map<String, Object> finishContentTranslationItemRow(Object... args) {
        return RepositoryResults.required(mapper.finishContentTranslationItemRow(args));
    }

    public int finishContentTranslationItemWrite(Object... args) {
        return mapper.finishContentTranslationItemWrite(args);
    }

    public int finishCompanyFileWrite(Object... args) {
        return mapper.finishCompanyFileWrite(args);
    }

    public List<Map<String, Object>> failContentTranslationItemRows(Object... args) {
        return ComputeData.normalizeRows(mapper.failContentTranslationItemRows(args));
    }

    public int failContentTranslationItemWrite(Object... args) {
        return mapper.failContentTranslationItemWrite(args);
    }

    public int refreshTaskContentTranslationTaskWrite(Object... args) {
        return mapper.refreshTaskContentTranslationTaskWrite(args);
    }

    public List<Map<String, Object>> taskViewContentTranslationItemRows(Object... args) {
        return ComputeData.normalizeRows(mapper.taskViewContentTranslationItemRows(args));
    }
}
