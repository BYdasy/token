package org.token.service.impl;

import org.token.tools.cache.HybridCacheService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.model.entity.Menu;
import org.token.service.MenuService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.time.Duration;

/**
 * 后台菜单服务实现。
 */
@Service
public class MenuServiceImpl implements MenuService {
    private final org.token.repository.system.MenuRepository repository;

    private static final String MENU_CACHE_KEY = "system:enabled-menus:v23";
    private static final TypeReference<List<Menu>> MENU_LIST = new TypeReference<>() { };

    private HybridCacheService cacheService;
    private ObjectMapper objectMapper;

    @Autowired
    public MenuServiceImpl(org.token.repository.system.MenuRepository repository,
                             HybridCacheService cacheService,ObjectMapper objectMapper) {
        this.repository=repository;this.cacheService=cacheService;this.objectMapper=objectMapper;
    }

    public MenuServiceImpl() { this(null,null,null); }

    void configureCache(HybridCacheService cacheService, ObjectMapper objectMapper) {
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    static final Set<Integer> RETAINED_MENU_IDS = org.token.custom.security.PermissionCodes.MENU_IDS;

    /**
     * 只返回开启状态的菜单，角色授权树和前端菜单都走这一份数据。
     */
    @Override
    @Transactional(readOnly = true)
    public List<Menu> getMenu(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            List<Menu> cached = readCachedMenus();
            if (cached != null) {
                return cached;
            }
        }
        List<Menu> result = repository.enabled(RETAINED_MENU_IDS, ids);
        if (ids == null || ids.isEmpty()) {
            writeCachedMenus(result);
        }
        return result;
    }

    private List<Menu> readCachedMenus() {
        if (cacheService == null || objectMapper == null) {
            return null;
        }
        String value = cacheService.get(MENU_CACHE_KEY);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return objectMapper.readValue(value, MENU_LIST);
        } catch (Exception exception) {
            cacheService.evict(MENU_CACHE_KEY);
            return null;
        }
    }

    private void writeCachedMenus(List<Menu> menus) {
        if (cacheService == null || objectMapper == null) {
            return;
        }
        try {
            cacheService.put(MENU_CACHE_KEY, objectMapper.writeValueAsString(menus), Duration.ofMinutes(10));
        } catch (Exception ignored) {
        }
    }
}
