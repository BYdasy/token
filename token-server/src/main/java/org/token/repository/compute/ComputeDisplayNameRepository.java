package org.token.repository.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import org.token.repository.support.*;
import org.token.repository.dao.compute.ComputeDisplayNameMapper;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

@Repository
public class ComputeDisplayNameRepository {
    private final ComputeDisplayNameMapper mapper;
    @Autowired public ComputeDisplayNameRepository(ComputeDisplayNameMapper mapper) { this.mapper = mapper; }
    public ComputeDisplayNameRepository(ComputeData data) { this(data.mapper(ComputeDisplayNameMapper.class)); }

    public List<Map<String, Object>> administratorNames(Collection<Long> ids) {
        return ComputeData.normalizeRows(mapper.administratorNames(ids));
    }

    public List<Map<String, Object>> memberNames(Collection<Long> ids) {
        return ComputeData.normalizeRows(mapper.memberNames(ids));
    }

    /** 商户客户号只按已登记永久归属显示姓名，不从文本推导会员编号。 */
    public List<Map<String, Object>> merchantCustomerNames(String merchantId, String environment, Collection<String> customerIds) {
        if (customerIds.isEmpty()) return List.of();
        return ComputeData.normalizeRows(mapper.merchantCustomerNames(merchantId, environment, customerIds));
    }
}
