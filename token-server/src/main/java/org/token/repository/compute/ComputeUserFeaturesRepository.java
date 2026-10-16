package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeUserFeaturesMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeUserFeaturesService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeUserFeaturesRepository {
    private final ComputeUserFeaturesMapper mapper;

    @Autowired
    public ComputeUserFeaturesRepository(ComputeUserFeaturesMapper mapper) { this.mapper = mapper; }

    public ComputeUserFeaturesRepository(ComputeData data) {
        this(data.mapper(ComputeUserFeaturesMapper.class));
    }

    public List<Map<String, Object>> adminSettingsActivityRows(Object... args) {
        return ComputeData.normalizeRows(mapper.adminSettingsActivityRows(args));
    }

    public int putSettingWrite(Object... args) {
        return mapper.putSettingWrite(args);
    }

    public List<Map<String, Object>> publicSettingsActivityRows(Object... args) {
        return ComputeData.normalizeRows(mapper.publicSettingsActivityRows(args));
    }

    public Map<String, Object> memberPreferencesMemberRow(Object... args) {
        return RepositoryResults.required(mapper.memberPreferencesMemberRow(args));
    }

    public Integer languageTranslateLanguageCount(Object... args) {
        return RepositoryResults.scalar(mapper.languageTranslateLanguageCount(args), Integer.class);
    }

    public int languageMemberWrite(Object... args) {
        return mapper.languageMemberWrite(args);
    }

    public List<String> lockFeatureSettings() {
        return mapper.lockFeatureSettings();
    }
}
