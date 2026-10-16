package org.token.service.compute;

import org.token.repository.compute.ComputeUserTimeZoneRepository;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneId;
import java.util.Map;
import static org.token.repository.compute.ComputeData.*;

/** 用户单独选择展示时区；空值始终跟随系统，不能改变业务时间规则。 */
@Service
public class ComputeUserTimeZoneService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeUserTimeZoneService(ComputeData data, SystemSettingService settings, ComputeUserTimeZoneRepository repository) {
        this.data = data;
        this.settings = settings;
        this.repository = repository;
    }

    public ComputeUserTimeZoneService(ComputeData data, SystemSettingService settings) {
        this(data, settings, new ComputeUserTimeZoneRepository(data));
    }

    private final ComputeData data;
    private final ComputeUserTimeZoneRepository repository;
    private final SystemSettingService settings;

    @Transactional
    public Map<String, Object> save(long memberId, String requested) {
        String value = text(requested);
        if (!value.isEmpty() && !ZoneId.getAvailableZoneIds().contains(value))
            throw new BusinessException("server.settings.timeZoneInvalid");
        if (repository.saveMemberWrite(value.isEmpty() ? null : value, memberId) != 1)
            throw new BusinessException(401, "server.compute.session.expired");
        return fields("userTimeZone", value, "systemTimeZone", settings.resolveSystemTimeZone());
    }
}
