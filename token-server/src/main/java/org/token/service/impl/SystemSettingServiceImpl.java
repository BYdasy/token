package org.token.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.token.tools.cache.HybridCacheService;
import org.token.tools.cache.CacheFeature;
import org.token.tools.cache.CacheMode;
import org.token.tools.cache.CacheRuntimePolicyService;
import org.token.model.entity.SystemSetting;
import org.token.model.request.system.SystemSettingsUpdateRequest;
import org.token.model.response.SystemSettingsView;
import org.token.model.response.SystemTimeView;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SystemSettingServiceImpl
        implements SystemSettingService {
    private final org.token.repository.system.SystemSettingRepository repository;

    public static final String SITE_NAME = "site.name";
    public static final String SITE_ICON = "site.icon";
    public static final String SITE_LOGO = "site.logo";
    public static final String USER_SITE_NAME = "user.site.name";
    public static final String USER_SITE_LOGO = "user.site.logo";
    public static final String USER_SITE_ICON = "user.site.icon";
    public static final String USER_HERO_IMAGE = "user.site.hero-image";
    public static final String USER_SHARE_ENTRY_URL = "user.share.entry-url";
    public static final String USER_SHARE_TITLE = "user.share.title";
    public static final String USER_SHARE_DESCRIPTION = "user.share.description";
    public static final String USER_SHARE_IMAGE = "user.share.image";
    public static final String TIME_ZONE = "system.time-zone";
    public static final String SERVER_DISPLAY_TIME_ZONE = "server.display-time-zone";
    public static final String USER_TIME_ZONE_MODE = "user.time-zone-mode";
    public static final String DATE_TIME_FORMAT = "system.date-time-format";
    public static final String IP_WHITELIST_ENABLED = "security.ip-whitelist.enabled";

    private static final String CACHE_KEY = "settings:all";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final HybridCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final String yamlTimeZone;

    @org.springframework.beans.factory.annotation.Autowired
    public SystemSettingServiceImpl(
            HybridCacheService cacheService,
            ObjectMapper objectMapper,
            @Value("${exp.time-zone:Asia/Shanghai}") String yamlTimeZone,
            org.token.repository.system.SystemSettingRepository repository
    ) {
        this.repository=repository;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.yamlTimeZone = yamlTimeZone;
    }

    public SystemSettingServiceImpl(HybridCacheService cacheService,ObjectMapper objectMapper,String yamlTimeZone) {
        this(cacheService,objectMapper,yamlTimeZone,null);
    }

    @Override
    public SystemSettingsView getSettings() {
        Map<String, String> values = loadAll();
        return buildView(values, true);
    }

    @Override
    public SystemSettingsView getPublicSettings() {
        return buildView(loadAll(), false);
    }

    @Override
    public SystemTimeView getServerTime() {
        return getServerTime(null);
    }

    @Override
    public SystemTimeView getServerTime(String accountTimeZone) {
        Map<String, String> settings = loadAll();
        ZoneId zoneId = resolveZone(resolveDisplayTimeZone(null));
        String dateTimeFormat = settings.getOrDefault(DATE_TIME_FORMAT, "yyyy-MM-dd HH:mm:ss");
        validateDateTimeFormat(dateTimeFormat);
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        return new SystemTimeView(
                zoneId.getId(),
                dateTimeFormat,
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                Instant.now().toEpochMilli(),
                resolveSystemTimeZone()
        );
    }

    @Override
    public String resolveDisplayTimeZone(String accountTimeZone) {
        return resolveDisplayTimeZoneUsingSystem(resolveSystemTimeZone());
    }

    @Override
    public String resolveDisplayTimeZoneUsingSystem(String system) {
        String configured = repository == null ? get(SERVER_DISPLAY_TIME_ZONE, system) : repository.selectServerDisplayTimeZone();
        return resolveZone(StringUtils.hasText(configured) ? configured : system).getId();
    }

    @Override
    public String resolveSystemTimeZone() {
        return resolveZone(currentSystemTimeZone(repository==null?get(TIME_ZONE,yamlTimeZone):yamlTimeZone)).getId();
    }

    /** 系统时区是业务规则，绕过展示配置缓存，避免多节点以旧时区反复重排任务。 */
    private String currentSystemTimeZone(String fallback) {
        if(repository==null)return fallback;
        try {
            String configured=repository.selectSystemTimeZone();
            return StringUtils.hasText(configured)?configured:yamlTimeZone;
        } catch (Exception exception) {
            // 数据库异常时不能按过期时区执行资金次数校验或定时调度。
            throw new BusinessException(503,"server.settings.systemTimeZoneUnavailable");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SystemSettingsView updateSettings(SystemSettingsUpdateRequest request, Integer operatorUid) {
        if (request == null) {
            throw new BusinessException("server.settings.required");
        }
        if (request.getSiteName() != null) {
            upsert(SITE_NAME, brandName(request.getSiteName()), "string", "site", "后台管理端名称", 1, operatorUid);
        }
        if (request.getSiteIcon() != null) {
            upsert(SITE_ICON, brandImage(request.getSiteIcon()), "url", "site", "后台管理端浏览器图标", 1, operatorUid);
        }
        if (request.getSiteLogo() != null) upsert(SITE_LOGO, brandImage(request.getSiteLogo()), "url", "site", "后台管理端标志", 1, operatorUid);
        if (request.getUserSiteName() != null) upsert(USER_SITE_NAME, brandName(request.getUserSiteName()), "string", "user-site", "用户端网站名称", 1, operatorUid);
        if (request.getUserSiteLogo() != null) upsert(USER_SITE_LOGO, brandImage(request.getUserSiteLogo()), "url", "user-site", "用户端标志", 1, operatorUid);
        if (request.getUserSiteIcon() != null) upsert(USER_SITE_ICON, brandImage(request.getUserSiteIcon()), "url", "user-site", "用户端浏览器图标", 1, operatorUid);
        if (request.getUserHeroImage() != null) upsert(USER_HERO_IMAGE, brandImage(request.getUserHeroImage()), "url", "user-site", "用户端首页图片", 1, operatorUid);
        if (request.getUserShareEntryUrl() != null) upsert(USER_SHARE_ENTRY_URL, org.token.service.compute.SharePreviewRules.siteUrl(request.getUserShareEntryUrl()), "url", "user-share", "用户端网站入口，留空按当前域名自动选择", 1, operatorUid);
        if (request.getUserShareTitle() != null) upsert(USER_SHARE_TITLE, org.token.service.compute.SharePreviewRules.text(request.getUserShareTitle(), 120), "string", "user-share", "用户站分享预览标题", 1, operatorUid);
        if (request.getUserShareDescription() != null) upsert(USER_SHARE_DESCRIPTION, org.token.service.compute.SharePreviewRules.text(request.getUserShareDescription(), 300), "string", "user-share", "用户站分享预览说明", 1, operatorUid);
        if (request.getUserShareImage() != null) upsert(USER_SHARE_IMAGE, org.token.service.compute.SharePreviewRules.image(request.getUserShareImage()), "url", "user-share", "用户站分享预览图片", 1, operatorUid);
        if (request.getTimeZone() != null) {
            ZoneId zone = resolveZone(request.getTimeZone());
            upsert(TIME_ZONE, zone.getId(), "timezone", "system", "系统业务时区：自然日、报表筛选、定时任务及用户默认时区", 1, operatorUid);
        }
        if (request.getServerDisplayTimeZone() != null) {
            ZoneId zone = resolveZone(request.getServerDisplayTimeZone());
            upsert(SERVER_DISPLAY_TIME_ZONE, zone.getId(), "timezone", "system", "后台管理端展示时区，不改变业务自然日及调度", 0, operatorUid);
        }
        if (request.getUserTimeZoneMode() != null) {
            String mode = request.getUserTimeZoneMode().trim();
            if (!List.of("USER", "SYSTEM").contains(mode)) throw new BusinessException("server.settings.userTimeZoneModeInvalid");
            upsert(USER_TIME_ZONE_MODE, mode, "enum", "system", "用户端时区来源", 1, operatorUid);
        }
        if (request.getDateTimeFormat() != null) {
            String format = request.getDateTimeFormat().trim();
            validateDateTimeFormat(format);
            upsert(DATE_TIME_FORMAT, format, "string", "system", "日期时间展示格式", 0, operatorUid);
        }
        if (request.getCacheEnabled() != null) {
            upsert(CacheRuntimePolicyService.CACHE_ENABLED, request.getCacheEnabled().toString(),
                    "boolean", "cache", "缓存总开关", 0, operatorUid);
        }
        if (request.getCacheMode() != null) {
            CacheMode mode = parseCacheMode(request.getCacheMode());
            upsert(CacheRuntimePolicyService.CACHE_MODE, mode.name(),
                    "enum", "cache", "缓存运行模式：MEMORY/REDIS/AUTO", 0, operatorUid);
        }
        updateCacheFeature(CacheFeature.SETTINGS, request.getCacheSettingsEnabled(), operatorUid, "基础设置缓存");
        updateCacheFeature(CacheFeature.LANGUAGES, request.getCacheLanguagesEnabled(), operatorUid, "语言目录与词条缓存");
        updateCacheFeature(CacheFeature.MENUS, request.getCacheMenusEnabled(), operatorUid, "菜单缓存");
        updateCacheFeature(CacheFeature.ROLES, request.getCacheRolesEnabled(), operatorUid, "角色权限缓存");
        updateCacheFeature(CacheFeature.IP_WHITELIST, request.getCacheIpWhitelistEnabled(), operatorUid, "IP 白名单缓存");
        cacheService.refreshPolicy();
        invalidateCache();
        return getSettings();
    }

    @Override
    public String get(String key, String fallback) {
        var rows=repository.values(key);
        String value = rows.isEmpty()?null:rows.get(0);
        return StringUtils.hasText(value) ? value : fallback;
    }

    @Override
    public boolean getBoolean(String key, boolean fallback) {
        var rows=repository.values(key);
        String value = rows.isEmpty()?null:rows.get(0);
        return StringUtils.hasText(value) ? Boolean.parseBoolean(value) : fallback;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateValue(String key, String value, Integer operatorUid) {
        if (java.util.Set.of(org.token.service.auth.LoginSessionPolicy.ADMIN_KEY,
                org.token.service.auth.LoginSessionPolicy.USER_KEY).contains(key)) {
            Integer hours;
            try { hours = Integer.valueOf(value); }
            catch (NumberFormatException exception) { throw new BusinessException("server.session.idleHoursInvalid"); }
            org.token.service.auth.LoginSessionPolicy.validateHours(hours);
            upsert(key, String.valueOf(hours), "number", "security", key.equals(org.token.service.auth.LoginSessionPolicy.ADMIN_KEY)
                    ? "后台登录空闲有效期，小时" : "用户端登录空闲有效期，小时", 0, operatorUid);
            invalidateCache();
            return;
        }
        if (!IP_WHITELIST_ENABLED.equals(key)) {
            throw new BusinessException("server.settings.keyNotAllowed");
        }
        upsert(key, String.valueOf(Boolean.parseBoolean(value)), "boolean", "security", "IP 白名单总开关", 0, operatorUid);
        invalidateCache();
    }

    @Override
    public void invalidateCache() {
        cacheService.evict(CACHE_KEY);
    }

    private Map<String, String> loadAll() {
        // 展示配置直接读库；合并后的奖励、提款和安全配置不得进入共享展示缓存。
        Map<String, String> values = new LinkedHashMap<>();
            List<SystemSetting> records = repository.presentationSettings();
            for (SystemSetting record : records) {
                if (record != null && StringUtils.hasText(record.getSettingKey())) {
                    values.put(record.getSettingKey(), record.getSettingValue());
                }
            }
        return values;
    }

    private SystemSettingsView buildView(Map<String, String> values, boolean includeCacheStatus) {
        String zone = currentSystemTimeZone(values.getOrDefault(TIME_ZONE, yamlTimeZone));
        ZoneId zoneId = resolveZone(zone);
        return SystemSettingsView.builder()
                .siteName(values.getOrDefault(SITE_NAME, "Tensor AI"))
                .siteIcon(values.getOrDefault(SITE_ICON, ""))
                .siteLogo(values.getOrDefault(SITE_LOGO, values.getOrDefault(SITE_ICON, "")))
                .userSiteName(values.getOrDefault(USER_SITE_NAME, "Tensor AI"))
                .userSiteLogo(values.getOrDefault(USER_SITE_LOGO, ""))
                .userSiteIcon(values.getOrDefault(USER_SITE_ICON, ""))
                .userHeroImage(values.getOrDefault(USER_HERO_IMAGE, ""))
                .userShareEntryUrl(values.getOrDefault(USER_SHARE_ENTRY_URL, ""))
                .userShareTitle(values.getOrDefault(USER_SHARE_TITLE, ""))
                .userShareDescription(values.getOrDefault(USER_SHARE_DESCRIPTION, ""))
                .userShareImage(values.getOrDefault(USER_SHARE_IMAGE, ""))
                .timeZone(zoneId.getId())
                .serverDisplayTimeZone(resolveDisplayTimeZone(null))
                .userTimeZoneMode("SYSTEM")
                .dateTimeFormat(values.getOrDefault(DATE_TIME_FORMAT, "yyyy-MM-dd HH:mm:ss"))
                .cache(includeCacheStatus ? cacheService.status() : null)
                .build();
    }

    private void upsert(
            String key, String value, String valueType, String groupName,
            String description, int isPublic, Integer operatorUid
    ) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setValueType(valueType);
        setting.setGroupName(groupName);
        setting.setDescription(description);
        setting.setIsPublic(isPublic);
        setting.setIsEditable(1);
        setting.setUpdatedBy(operatorUid);
        repository.saveOrUpdate(setting);
    }

    private String brandName(String value) {
        String name = value.trim();
        if (name.length() > 80) throw new BusinessException("server.settings.brandInvalid");
        return name;
    }

    private String brandImage(String value) {
        String image = value.trim();
        if (image.isEmpty()) return image;
        if (image.length() > 2048 || image.contains("\\") || image.startsWith("//")) throw new BusinessException("server.settings.brandInvalid");
        try {
            java.net.URI uri = java.net.URI.create(image);
            if ((!image.startsWith("/") && !List.of("https", "http").contains(uri.getScheme())) || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw new BusinessException("server.settings.brandInvalid"); }
        return image;
    }

    private ZoneId resolveZone(String value) {
        try {
            return ZoneId.of(StringUtils.hasText(value) ? value.trim() : yamlTimeZone);
        } catch (Exception exception) {
            throw new BusinessException("server.settings.timeZoneInvalid");
        }
    }

    private void validateDateTimeFormat(String format) {
        if (!StringUtils.hasText(format) || format.length() > 64) {
            throw new BusinessException("server.settings.dateTimeFormatInvalid");
        }
        try {
            DateTimeFormatter.ofPattern(format);
        } catch (Exception exception) {
            throw new BusinessException("server.settings.dateTimeFormatInvalid");
        }
    }

    private CacheMode parseCacheMode(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("server.settings.cacheModeInvalid");
        }
        CacheMode mode = CacheMode.parse(value, null);
        if (mode == null) {
            throw new BusinessException("server.settings.cacheModeInvalid");
        }
        return mode;
    }

    private void updateCacheFeature(
            CacheFeature feature,
            Boolean enabled,
            Integer operatorUid,
            String description
    ) {
        if (enabled == null) {
            return;
        }
        upsert(feature.settingKey(), enabled.toString(), "boolean", "cache", description, 0, operatorUid);
    }
}
