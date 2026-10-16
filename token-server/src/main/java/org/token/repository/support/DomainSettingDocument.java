package org.token.repository.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.compute.ComputeData;
import java.util.LinkedHashMap;
import java.util.Map;

/** 统一配置表中的领域文档编解码；业务生效时间与配置更新时间独立保存。 */
public final class DomainSettingDocument {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final TypeReference<Map<String,Object>> MAP = new TypeReference<>() {};
    private DomainSettingDocument() {}

    public static Map<String,Object> unpack(Map<String,Object> raw, String bodyField,
                                             String timeField, String projectedTimeField) {
        Map<String,Object> result = new LinkedHashMap<>(raw.containsKey(bodyField) ? raw : ComputeData.normalizeRow(raw));
        try {
            Map<String,Object> document = JSON.readValue(ComputeData.text(result.get(bodyField)), MAP);
            if (!(document.get("configuration") instanceof Map<?,?>))
                throw new IllegalStateException("领域配置文档缺少 configuration");
            result.put(bodyField, JSON.writeValueAsString(document.get("configuration")));
            result.put(projectedTimeField, document.get(timeField));
            return result;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("领域配置文档格式无效", failure);
        }
    }
}
