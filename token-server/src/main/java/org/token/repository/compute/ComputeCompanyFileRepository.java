package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeCompanyFileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeCompanyFileService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeCompanyFileRepository {
    private final ComputeCompanyFileMapper mapper;

    @Autowired
    public ComputeCompanyFileRepository(ComputeCompanyFileMapper mapper) { this.mapper = mapper; }

    public ComputeCompanyFileRepository(ComputeData data) {
        this(data.mapper(ComputeCompanyFileMapper.class));
    }

    public List<Map<String, Object>> userListCompanyFileRows(Object... args) {
        return ComputeData.normalizeRows(mapper.userListCompanyFileRows(args));
    }

    public List<Map<String, Object>> updateCompanyFileRows(Object... args) {
        return ComputeData.normalizeRows(mapper.updateCompanyFileRows(args));
    }

    public int updateCompanyFileWrite(Object... args) {
        return mapper.updateCompanyFileWrite(args);
    }

    public Map<String, Object> adminFile(long id) {
        return RepositoryResults.required(mapper.adminFile(id));
    }

    public List<Map<String, Object>> pdfFile(long id, boolean admin) {
        return ComputeData.normalizeRows(mapper.pdfFile(id, admin));
    }

    public Long countAdminFiles(String keyword, Integer enabled) {
        return mapper.countAdminFiles(keyword, enabled);
    }

    public List<Map<String, Object>> adminFiles(String keyword, Integer enabled, int limit, long offset) {
        return ComputeData.normalizeRows(mapper.adminFiles(keyword, enabled, limit, offset));
    }
}
