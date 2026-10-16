package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeAutoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeAutoService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeAutoRepository {
    private final ComputeAutoMapper mapper;

    @Autowired
    public ComputeAutoRepository(ComputeAutoMapper mapper) { this.mapper = mapper; }

    public ComputeAutoRepository(ComputeData data) {
        this(data.mapper(ComputeAutoMapper.class));
    }

    public List<Map<String, Object>> recordsAutoContractRows(Object... args) {
        return ComputeData.normalizeRows(mapper.recordsAutoContractRows(args));
    }

    public Long recordsAutoContractCount(Object... args) {
        return RepositoryResults.scalar(mapper.recordsAutoContractCount(args), Long.class);
    }

    public Map<String, Object> activateMemberRow(Object... args) {
        return RepositoryResults.required(mapper.activateMemberRow(args));
    }

    public List<Map<String, Object>> activateAutoContractRows(Object... args) {
        return ComputeData.normalizeRows(mapper.activateAutoContractRows(args));
    }

    public Map<String, Object> activateLevelRow(Object... args) {
        return RepositoryResults.required(mapper.activateLevelRow(args));
    }

    public List<Map<String, Object>> activateWalletRows(Object... args) {
        return ComputeData.normalizeRows(mapper.activateWalletRows(args));
    }

    public int activateMemberWrite(Object... args) {
        return mapper.activateMemberWrite(args);
    }

    public Map<String, Object> recordAutoContractRow(Object... args) {
        return RepositoryResults.required(mapper.recordAutoContractRow(args));
    }

    public Map<String, Object> statusMemberRow(Object... args) {
        return RepositoryResults.required(mapper.statusMemberRow(args));
    }

    public Map<String, Object> statusLevelRow(Object... args) {
        return RepositoryResults.required(mapper.statusLevelRow(args));
    }

    public Long statusOfAutoContractCount(Object... args) {
        return RepositoryResults.scalar(mapper.statusOfAutoContractCount(args), Long.class);
    }

    public List<Map<String, Object>> statusOfWalletRows(Object... args) {
        return ComputeData.normalizeRows(mapper.statusOfWalletRows(args));
    }


    public List<Map<String, Object>> statusOfAutoContractRows2(Object... args) {
        return ComputeData.normalizeRows(mapper.statusOfAutoContractRows2(args));
    }


    public List<Map<String, Object>> finishDueAutoContractRows2(Object... args) {
        return ComputeData.normalizeRows(mapper.finishDueAutoContractRows2(args));
    }

    public Map<String, Object> finishDetailsAutoContractRow(Object... args) {
        return RepositoryResults.required(mapper.finishDetailsAutoContractRow(args));
    }

    public int finishDetailsAutoContractWrite(Object... args) {
        return mapper.finishDetailsAutoContractWrite(args);
    }





}
