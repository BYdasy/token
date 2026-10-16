package org.token.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.token.tools.cache.HybridCacheService;
import org.token.model.entity.IpWhitelistEntry;
import org.token.model.request.system.IpWhitelistSaveRequest;
import org.token.exception.BusinessException;
import org.token.service.IpWhitelistService;
import org.token.service.SystemSettingService;
import org.token.util.IpCidrMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class IpWhitelistServiceImpl
        implements IpWhitelistService {
    private final org.token.repository.system.IpWhitelistRepository repository;

    private static final String CACHE_KEY = "security:ip-whitelist";
    private static final TypeReference<List<IpWhitelistEntry>> ENTRY_LIST = new TypeReference<>() { };

    private final SystemSettingService settingService;
    private final HybridCacheService cacheService;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public IpWhitelistServiceImpl(
            SystemSettingService settingService,
            HybridCacheService cacheService,
            ObjectMapper objectMapper,
            org.token.repository.system.IpWhitelistRepository repository
    ) {
        this.repository=repository;
        this.settingService = settingService;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    public IpWhitelistServiceImpl(SystemSettingService settingService,HybridCacheService cacheService,ObjectMapper objectMapper) {
        this(settingService,cacheService,objectMapper,null);
    }

    @Override
    public List<IpWhitelistEntry> listAll() {
        try {
            return repository.findAllOrdered();
        } catch (Exception exception) {
            log.warn("读取 IP 白名单失败：{}", exception.getMessage());
            return List.of();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IpWhitelistEntry create(IpWhitelistSaveRequest request, Integer operatorUid) {
        IpWhitelistEntry entry = fromRequest(request);
        entry.setId(null);
        entry.setCreatedBy(operatorUid);
        if (!repository.save(entry)) {
            throw new BusinessException("server.ipWhitelist.createFailed");
        }
        invalidateCache();
        return entry;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IpWhitelistEntry update(
            IpWhitelistSaveRequest request,
            String currentClientIp,
            boolean directLocalRequest
    ) {
        if (request == null || request.getId() == null || request.getId() <= 0) {
            throw new BusinessException("server.common.id.required");
        }
        IpWhitelistEntry entry = fromRequest(request);
        entry.setId(request.getId());
        if (!repository.updateById(entry)) {
            throw new BusinessException("server.ipWhitelist.updateFailed");
        }
        invalidateCache();
        assertCurrentClientRemainsAllowed(currentClientIp, directLocalRequest);
        return repository.getById(entry.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, String currentClientIp, boolean directLocalRequest) {
        if (id == null || id <= 0) {
            throw new BusinessException("server.common.id.required");
        }
        if (!repository.removeById(id)) {
            throw new BusinessException("server.ipWhitelist.deleteFailed");
        }
        invalidateCache();
        assertCurrentClientRemainsAllowed(currentClientIp, directLocalRequest);
    }

    @Override
    public boolean isEnabled() {
        return settingService.getBoolean(SystemSettingServiceImpl.IP_WHITELIST_ENABLED, false);
    }

    @Override
    public void setEnabled(
            boolean enabled,
            Integer operatorUid,
            String currentClientIp,
            boolean directLocalRequest
    ) {
        if (enabled && activeRules().isEmpty()) {
            throw new BusinessException("server.ipWhitelist.empty");
        }
        if (enabled && !directLocalRequest && !isAllowed(currentClientIp)) {
            throw new BusinessException("server.ipWhitelist.currentIpNotAllowed");
        }
        settingService.updateValue(
                SystemSettingServiceImpl.IP_WHITELIST_ENABLED,
                String.valueOf(enabled),
                operatorUid
        );
    }

    @Override
    public boolean isAllowed(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        return activeRules().stream().anyMatch(entry -> IpCidrMatcher.matches(entry.getIpPattern(), ip));
    }

    @Override
    public long activeRuleCount() {
        return activeRules().size();
    }

    @Override
    public void invalidateCache() {
        cacheService.evict(CACHE_KEY);
    }

    private List<IpWhitelistEntry> activeRules() {
        String cached = cacheService.get(CACHE_KEY);
        if (StringUtils.hasText(cached)) {
            try {
                return objectMapper.readValue(cached, ENTRY_LIST);
            } catch (Exception exception) {
                cacheService.evict(CACHE_KEY);
            }
        }
        List<IpWhitelistEntry> rules;
        try {
            rules = repository.enabled();
        } catch (Exception exception) {
            // SQL 尚未执行时保持关闭逻辑可启动；若开关已经开启则无规则会拒绝远程请求。
            log.warn("读取启用的 IP 白名单失败：{}", exception.getMessage());
            rules = List.of();
        }
        try {
            cacheService.put(CACHE_KEY, objectMapper.writeValueAsString(rules), Duration.ofMinutes(5));
        } catch (Exception ignored) {
        }
        return rules;
    }

    private IpWhitelistEntry fromRequest(IpWhitelistSaveRequest request) {
        if (request == null || !StringUtils.hasText(request.getIpPattern())) {
            throw new BusinessException("server.ipWhitelist.patternRequired");
        }
        String normalized;
        try {
            normalized = IpCidrMatcher.normalizeRule(request.getIpPattern());
        } catch (Exception exception) {
            throw new BusinessException("server.ipWhitelist.patternInvalid");
        }
        IpWhitelistEntry entry = new IpWhitelistEntry();
        entry.setIpPattern(normalized);
        entry.setDescription(StringUtils.hasText(request.getDescription()) ? request.getDescription().trim() : null);
        entry.setEnabled(request.getEnabled() != null && request.getEnabled() == 0 ? 0 : 1);
        return entry;
    }

    private void assertCurrentClientRemainsAllowed(String currentClientIp, boolean directLocalRequest) {
        if (!isEnabled() || directLocalRequest) {
            return;
        }
        if (!isAllowed(currentClientIp)) {
            throw new BusinessException("server.ipWhitelist.lockoutRisk");
        }
    }
}
