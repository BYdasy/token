package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeJobMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeJobService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeJobRepository {
    private final ComputeJobMapper mapper;

    @Autowired
    public ComputeJobRepository(ComputeJobMapper mapper) { this.mapper = mapper; }

    public ComputeJobRepository(ComputeData data) {
        this(data.mapper(ComputeJobMapper.class));
    }

    public List<Map<String, Object>> jobsJobRows(Object... args) {
        return ComputeData.normalizeRows(mapper.jobsJobRows(args));
    }

    public List<Map<String, Object>> synchronizeScheduleZoneJobRows(Object... args) {
        return ComputeData.normalizeRows(mapper.synchronizeScheduleZoneJobRows(args));
    }

    public Map<String, Object> synchronizeScheduleZoneJobRow(Object... args) {
        return RepositoryResults.required(mapper.synchronizeScheduleZoneJobRow(args));
    }

    public int synchronizeScheduleZoneJobWrite(Object... args) {
        return mapper.synchronizeScheduleZoneJobWrite(args);
    }

    public Map<String, Object> updateJobRow(Object... args) {
        return RepositoryResults.required(mapper.updateJobRow(args));
    }

    public int updateJobWrite(Object... args) {
        return mapper.updateJobWrite(args);
    }

    public int updateJobWrite2(Object... args) {
        return mapper.updateJobWrite2(args);
    }

    public Map<String, Object> updateJobRow2(Object... args) {
        return RepositoryResults.required(mapper.updateJobRow2(args));
    }

    public Map<String, Object> submitJobRow(Object... args) {
        return RepositoryResults.required(mapper.submitJobRow(args));
    }

    public int submitJobWrite(Object... args) {
        return mapper.submitJobWrite(args);
    }

    public Map<String, Object> submitJobRunRow(Object... args) {
        return RepositoryResults.required(mapper.submitJobRunRow(args));
    }

    public List<Map<String, Object>> scheduleJobRunRows(Object... args) {
        return ComputeData.normalizeRows(mapper.scheduleJobRunRows(args));
    }

    public List<Map<String, Object>> scheduleJobRows(Object... args) {
        return ComputeData.normalizeRows(mapper.scheduleJobRows(args));
    }

    public int executeJobRunWrite(Object... args) {
        return mapper.executeJobRunWrite(args);
    }

    public List<Map<String, Object>> executeOrderRows(Object... args) {
        return ComputeData.normalizeRows(mapper.executeOrderRows(args));
    }

    public List<Map<String, Object>> executeMemberRows(Object... args) {
        return ComputeData.normalizeRows(mapper.executeMemberRows(args));
    }


    public List<Map<String, Object>> refreshModelsModelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.refreshModelsModelRows(args));
    }

    public Map<String, Object> refreshModelsModelRow(Object... args) {
        return RepositoryResults.required(mapper.refreshModelsModelRow(args));
    }

    public int refreshModelsModelPriceWrite(Object... args) {
        return mapper.refreshModelsModelPriceWrite(args);
    }

    public Map<String, Object> refreshModelsModelPriceRow(Object... args) {
        return RepositoryResults.required(mapper.refreshModelsModelPriceRow(args));
    }

    public int refreshModelsModelWrite(Object... args) {
        return mapper.refreshModelsModelWrite(args);
    }





    public int progressJobRunWrite(Object... args) {
        return mapper.progressJobRunWrite(args);
    }

    public Map<String, Object> finishJobRunRow(Object... args) {
        return RepositoryResults.required(mapper.finishJobRunRow(args));
    }

    public int finishJobRunWrite(Object... args) {
        return mapper.finishJobRunWrite(args);
    }

    public int finishJobWrite(Object... args) {
        return mapper.finishJobWrite(args);
    }
}
