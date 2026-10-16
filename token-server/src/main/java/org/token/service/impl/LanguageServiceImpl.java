package org.token.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.token.config.EnabledLocaleResolver;
import org.token.model.entity.Language;
import org.token.model.request.FilterParams;
import org.token.model.request.QueryParams;
import org.token.exception.BusinessException;
import org.token.service.LanguageService;
import org.token.util.LanguageDisplayNames;
import org.token.tools.media.SundryUtils;
import org.token.util.SystemDataProtection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统多语言配置服务实现。
 */
@Service
public class LanguageServiceImpl
        implements LanguageService {
    private final org.token.repository.system.LanguageRepository repository;

    private final EnabledLocaleResolver localeResolver;

    @org.springframework.beans.factory.annotation.Autowired
    public LanguageServiceImpl(EnabledLocaleResolver localeResolver,org.token.repository.system.LanguageRepository repository) {
        this.localeResolver=localeResolver;this.repository=repository;
    }

    public LanguageServiceImpl(EnabledLocaleResolver localeResolver) { this(localeResolver,null); }

    @Override
    @Transactional(readOnly = true)
    public List<Language> listLanguages(Boolean enabledOnly) {
        return repository.findOrdered(Boolean.TRUE.equals(enabledOnly));
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<Language> pageLanguages(QueryParams params) {
        FilterParams filter = params == null ? null : params.getFilterParams();
        String name = filter == null ? "" : trimToEmpty(filter.getName());
        String pattern = "%" + name.toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";

        long current = 1L;
        long size = 20L;
        if (params != null && params.getPage() != null) {
            if (params.getPage().getCurrent() != null && params.getPage().getCurrent() > 0) {
                current = params.getPage().getCurrent();
            }
            if (params.getPage().getSize() != null && params.getPage().getSize() > 0) {
                size = Math.min(params.getPage().getSize(), 500);
            }
        }
        return repository.findPage(current,size,params,pattern);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Language updateLanguage(Language request) {
        if (request == null || request.getId() == null) {
            throw new BusinessException("server.common.id.required");
        }

        Language record = repository.getById(request.getId());
        if (record == null) {
            throw new BusinessException("server.language.notFound");
        }

        if (SystemDataProtection.isBaseLanguage(record.getLang())
                && Integer.valueOf(0).equals(request.getStatus())) {
            throw new BusinessException("server.language.defaultDisableForbidden");
        }
        if ("en".equalsIgnoreCase(record.getLang()) && request.getStatus() != null
                && !Integer.valueOf(1).equals(request.getStatus())) {
            throw new BusinessException("server.language.englishRequired");
        }

        if (request.getStatus() != null) {
            record.setStatus(request.getStatus());
        }
        if (request.getSortNo() != null) {
            record.setSortNo(request.getSortNo());
        }
        record.setUpdateTime(SundryUtils.getTime());
        repository.updateById(record);
        localeResolver.invalidateCache();
        return record;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> overview(Boolean enabledOnly) {
        List<Language> languages = listLanguages(enabledOnly);
        List<Map<String, Object>> options = languages.stream()
                .map(this::option)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", languages.size());
        result.put("options", options);
        result.put("languages", languages);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> enabledLanguageCodes() {
        return listLanguages(true)
                .stream()
                .map(Language::getLang)
                .filter(StringUtils::hasText)
                .toList();
    }

    private Map<String, Object> option(Language language) {
        Map<String, Object> item = new LinkedHashMap<>();
        String chineseName = LanguageDisplayNames.chinese(language.getLang(), language.getChineseName(), language.getName());
        // 此接口只供后台编辑器使用；用户端语言切换继续保留原语言名称。
        item.put("label", chineseName.isEmpty() ? language.getLang() : chineseName);
        item.put("value", language.getLang());
        item.put("lang", language.getLang());
        item.put("name", language.getName());
        item.put("chineseName", language.getChineseName());
        item.put("nativeName", language.getNativeName());
        item.put("status", language.getStatus());
        item.put("sortNo", language.getSortNo());
        return item;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
