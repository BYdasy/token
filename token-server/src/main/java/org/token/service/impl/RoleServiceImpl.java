package org.token.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.tools.cache.HybridCacheService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.model.entity.Role;
import org.token.model.request.QueryParams;
import org.token.exception.BusinessException;
import org.token.service.RoleService;
import org.token.tools.media.SundryUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.time.Duration;

/**
 * 后台角色服务实现
 */
@Service
public class RoleServiceImpl
        implements RoleService {
    private final org.token.repository.system.RoleRepository repository;

    private static final String ROLE_LIST_CACHE_KEY = "system:roles:all";
    private static final String ROLE_CACHE_PREFIX = "system:role:";
    private static final TypeReference<List<Role>> ROLE_LIST = new TypeReference<>() { };

    private HybridCacheService cacheService;
    private ObjectMapper objectMapper;

    @Autowired
    public RoleServiceImpl(org.token.repository.system.RoleRepository repository,
                             HybridCacheService cacheService,ObjectMapper objectMapper) {
        this.repository=repository;this.cacheService=cacheService;this.objectMapper=objectMapper;
    }

    public RoleServiceImpl() { this(null,null,null); }

    void configureCache(HybridCacheService cacheService, ObjectMapper objectMapper) {
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取全部角色
     *
     * 保留旧逻辑：
     * 角色编号不等于 2。
     */
    @Override
    @Transactional(readOnly = true)
    public List<Role> getAll() {
        List<Role> cached = readListCache();
        if (cached != null) {
            return cached;
        }
        List<Role> roles = repository.findAllOrdered();
        writeCache(ROLE_LIST_CACHE_KEY, roles);
        return roles;
    }

    /**
     * 分页查询角色列表
     */
    @Override
    @Transactional(readOnly = true)
    public Page<Role> getList(QueryParams params) {
        long current = getCurrent(params);
        long size = getSize(params);

        return repository.findPage(current, size, params);
    }

    /**
     * 根据 ID 查询角色
     */
    @Override
    @Transactional(readOnly = true)
    public Role getRole(Integer id) {
        if (id == null || id <= 0) {
            return null;
        }

        Role cached = readRoleCache(id);
        if (cached != null) {
            return cached;
        }
        Role role = getById(id);
        if (role != null) {
            writeCache(ROLE_CACHE_PREFIX + id, role);
        }
        return role;
    }

    /**
     * 更新角色
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(Role role) {
        if (role == null || role.getId() == null) {
            throw new BusinessException("server.role.idRequired");
        }

        Role current = getById(role.getId());
        if (current == null) {
            throw new BusinessException("server.role.notFound");
        }
        if (Integer.valueOf(1).equals(current.getIsSuper())
                && Integer.valueOf(0).equals(role.getIsSuper())) {
            throw new BusinessException("server.role.defaultDowngradeForbidden");
        }

        role.setActionTime(SundryUtils.getTime());

        boolean updated = repository.updateById(role);

        if (!updated) {
            throw new BusinessException("server.role.updateFailed");
        }
        evictRoleCaches(role.getId());
    }

    /**
     * 删除角色
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Integer id) {
        if (id == null || id <= 0) {
            throw new BusinessException("server.role.idRequired");
        }

        Role role = getById(id);
        if (role == null) {
            throw new BusinessException("server.role.deleteFailed");
        }

        if (Integer.valueOf(1).equals(role.getIsSuper())) {
            throw new BusinessException("server.role.cannotDeleteDefault");
        }

        boolean removed = repository.removeById(id);

        if (!removed) {
            throw new BusinessException("server.role.deleteFailed");
        }
        evictRoleCaches(id);
    }

    /**
     * 创建角色
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createRole(String name, String remarks, int isSuper) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException("server.role.nameRequired");
        }

        Role role = new Role();
        role.setName(name.trim());
        role.setRemarks(StringUtils.hasText(remarks) ? remarks.trim() : "");
        role.setIsSuper(isSuper);
        role.setActionTime(SundryUtils.getTime());

        repository.save(role);
        if (cacheService != null) {
            cacheService.evict(ROLE_LIST_CACHE_KEY);
        }
    }

    /**
     * 排序白名单
     *
     * 不直接使用前端传入的 prop 字符串，避免 SQL 注入。
     */
    private long getCurrent(QueryParams params) {
        if (params != null
                && params.getPage() != null
                && params.getPage().getCurrent() != null
                && params.getPage().getCurrent() > 0) {
            return params.getPage().getCurrent();
        }

        return 1L;
    }

    private long getSize(QueryParams params) {
        long size = 10L;

        if (params != null
                && params.getPage() != null
                && params.getPage().getSize() != null
                && params.getPage().getSize() > 0) {
            size = params.getPage().getSize();
        }

        return Math.min(size, 200L);
    }

    private String trimToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<Role> readListCache() {
        String value = cacheService == null ? null : cacheService.get(ROLE_LIST_CACHE_KEY);
        if (!StringUtils.hasText(value) || objectMapper == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, ROLE_LIST);
        } catch (Exception exception) {
            cacheService.evict(ROLE_LIST_CACHE_KEY);
            return null;
        }
    }

    private Role readRoleCache(Integer id) {
        String value = cacheService == null ? null : cacheService.get(ROLE_CACHE_PREFIX + id);
        if (!StringUtils.hasText(value) || objectMapper == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Role.class);
        } catch (Exception exception) {
            cacheService.evict(ROLE_CACHE_PREFIX + id);
            return null;
        }
    }

    private void writeCache(String key, Object value) {
        if (cacheService == null || objectMapper == null || value == null) {
            return;
        }
        try {
            cacheService.put(key, objectMapper.writeValueAsString(value), Duration.ofMinutes(10));
        } catch (Exception ignored) {
        }
    }

    private void evictRoleCaches(Integer id) {
        if (cacheService != null) {
            cacheService.evict(ROLE_LIST_CACHE_KEY);
            if (id != null) {
                cacheService.evict(ROLE_CACHE_PREFIX + id);
            }
        }
    }
    @Override
    public Role getById(java.io.Serializable id) { return repository.getById(id); }
}
