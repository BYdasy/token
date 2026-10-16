package org.token.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.token.config.EnabledLocaleResolver;
import org.token.model.entity.Translation;
import org.token.model.request.FilterParams;
import org.token.model.request.QueryParams;
import org.token.model.response.AdminI18nBundle;
import org.token.exception.BusinessException;
import org.token.service.TranslationService;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeSystemMessageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 翻译服务实现
 */
@Service
public class TranslationServiceImpl
        implements TranslationService {
    private final org.token.repository.system.TranslationRepository repository;

    private static final int SYSTEM_MESSAGE_TEMPLATE_TYPE = 1;

    private static final int ADMIN_LANGUAGE_TEMPLATE_TYPE = 5;

    private static final Set<Integer> READ_ONLY_PACKAGE_TYPES = Set.of(5, 7);

    private final EnabledLocaleResolver localeResolver;
    private final ComputeSystemMessageService systemMessages;
    private final ComputeAgentScope computeScope;
    private final org.token.tools.cache.TranslationBodyCache bodyCache;

    @Autowired
    public TranslationServiceImpl(EnabledLocaleResolver localeResolver, ComputeSystemMessageService systemMessages,
                                  ComputeAgentScope computeScope, org.token.tools.cache.TranslationBodyCache bodyCache,
                                  org.token.repository.system.TranslationRepository repository) {
        this.repository=repository;
        this.localeResolver = localeResolver;
        this.systemMessages = systemMessages;
        this.computeScope = computeScope;
        this.bodyCache = bodyCache;
    }

    public TranslationServiceImpl(EnabledLocaleResolver localeResolver, ComputeSystemMessageService systemMessages,
                                  ComputeAgentScope computeScope, org.token.tools.cache.TranslationBodyCache bodyCache) {
        this(localeResolver,systemMessages,computeScope,bodyCache,null);
    }

    public TranslationServiceImpl(EnabledLocaleResolver localeResolver, ComputeSystemMessageService systemMessages,
                                  ComputeAgentScope computeScope) {
        this(localeResolver, systemMessages, computeScope, null);
    }

    public TranslationServiceImpl(EnabledLocaleResolver localeResolver) {
        this(localeResolver, null, null, null);
    }

    /**
     * 判断某个 key + lang 是否存在。
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isExist(String key, String lang, Integer type) {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(lang) || type == null) {
            return false;
        }

        Long count = repository.countByKeyLanguageType(key,localeResolver.normalize(lang),type);

        return count != null && count > 0;
    }

    /**
     * 删除翻译。
     *
     * 必须同时限定 type，避免相同 key 在另一个模板中的记录被误删。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTranslation(String key, Integer type) {
        if (!StringUtils.hasText(key) || type == null) {
            throw new BusinessException("server.translate.key.required");
        }
        if(ComputeSystemMessageService.systemKey(key))throw new BusinessException("server.v7.translation.systemTemplateProtected");

        repository.deleteKey(key,type);
        translationsChanged(type);
    }

    /**
     * 根据 key + type 查询翻译。
     */
    @Override
    @Transactional(readOnly = true)
    public List<Translation> getTranslationBatch(String key, Integer type) {
        if (!StringUtils.hasText(key) || type == null) {
            return List.of();
        }

        return repository.findBatch(key,type);
    }

    /**
     * 获取 Java 服务端消息。
     *
     * 只读取 type=1 的 server.* 键，并按照“精确语言、基础语言、英语”回退；
     * 运行时绝不自动翻译或写入数据库。
     */
    @Override
    public String getTranslate(String key, String lang) {
        if (!StringUtils.hasText(key)) {
            return "";
        }

        String targetLanguage = localeResolver.resolve(lang);
        List<String> candidates=localeResolver.lookupCandidates(targetLanguage).stream().map(localeResolver::normalize).distinct().toList();
        if(candidates.isEmpty())return key;
        return bodyCache == null ? loadServerMessage(key, candidates)
                : bodyCache.message(key + "\n" + String.join(",", candidates), () -> loadServerMessage(key, candidates));
    }

    private String loadServerMessage(String key, List<String> candidates) {
        // 每个接口都会取响应词条；一次读取全部回退候选，避免缺地区译文时连续往返数据库。
        List<Translation> translations=repository.findLanguageCandidates(key,SYSTEM_MESSAGE_TEMPLATE_TYPE,candidates);
        Map<String,Translation> latest=new LinkedHashMap<>();
        for(Translation translation:translations)latest.putIfAbsent(localeResolver.normalize(translation.getLang()),translation);
        for (String candidate : candidates) {
            Translation translation = latest.get(candidate);
            if (translation != null && StringUtils.hasText(translation.getTvalue())) {
                return translation.getTvalue();
            }
        }
        return key;
    }

    /**
     * 获取管理端 type=5 的 admin.* 动态语言包。
     */
    @Override
    public AdminI18nBundle getAdminI18nBundle(String lang) {
        String targetLanguage = localeResolver.resolve(lang);
        List<String> candidates = localeResolver.lookupCandidates(targetLanguage);
        Map<String, String> messages = bodyCache == null ? loadBundle(5, "admin.", candidates)
                : bodyCache.messages(5, String.join(",", candidates), () -> loadBundle(5, "admin.", candidates));
        return new AdminI18nBundle(targetLanguage, localeResolver.enabledLanguages(), Map.copyOf(messages));
    }

    @Override
    public Map<String, String> getUserI18nMessages(String lang) {
        String language = localeResolver.resolve(lang);
        List<String> candidates = localeResolver.lookupCandidates(language);
        return bodyCache == null ? loadBundle(7, "user.", candidates)
                : bodyCache.messages(7, String.join(",", candidates), () -> loadBundle(7, "user.", candidates));
    }

    @Override
    public void translationsChanged(Integer type) {
        if (bodyCache != null) bodyCache.translationsChanged(type);
    }

    private Map<String, String> loadBundle(int type, String prefix, List<String> candidates) {
        Map<String, String> messages = new LinkedHashMap<>();

        for (int index = candidates.size() - 1; index >= 0; index--) {
            List<Translation> records = repository.findBundle(type,candidates.get(index),prefix);
            for (Translation record : records) {
                if (StringUtils.hasText(record.getTkey())
                        && (type == 7 || record.getTkey().startsWith(prefix))
                        && StringUtils.hasText(record.getTvalue())) {
                    messages.put(record.getTkey(), record.getTvalue());
                }
            }
        }

        return messages;
    }

    /**
     * 根据 type 查询中文翻译。
     */
    @Override
    @Transactional(readOnly = true)
    public List<Translation> getTranslation(Integer type) {
        if (type == null) {
            return List.of();
        }

        return repository.findChinese(type);
    }

    /**
     * 分页查询翻译。
     *
     * 保留旧逻辑：
     * 1. lang 固定查询 zh
     * 2. type != 0
     * 3. 支持 key / langTitle / type / prop 条件
     */
    @Override
    @Transactional(readOnly = true)
    public IPage<Translation> getTranslationPage(QueryParams queryParams) {
        FilterParams filterParams = queryParams == null ? null : queryParams.getFilterParams();
        String type = filterParams == null ? "" : trimToEmpty(filterParams.getType());
        Integer selectedType = resolveTemplateTypeForRead(type);
        boolean global = computeScope != null && computeScope.isGlobalScope();

        long current = 1L;
        long size = 10L;

        if (queryParams != null && queryParams.getPage() != null) {
            Integer queryCurrent = queryParams.getPage().getCurrent();
            Integer querySize = queryParams.getPage().getSize();

            if (queryCurrent != null && queryCurrent > 0) {
                current = queryCurrent;
            }

            if (querySize != null && querySize > 0) {
                size = querySize;
            }
        }

        // 防止前端传超大 size，一次性查太多数据
        size = Math.min(size, 200L);

        IPage<Translation> result=repository.findPage(current,size,queryParams,selectedType,global,ComputeSystemMessageService.KEYS);
        if(global&&systemMessages!=null){Map<String,Boolean> settings=systemMessages.settings();for(Translation item:result.getRecords())if(ComputeSystemMessageService.systemKey(item.getTkey())&&Integer.valueOf(6).equals(item.getType())){
            String event=ComputeSystemMessageService.eventForKey(item.getTkey());item.setSystemMessageEvent(event);item.setSystemMessageEnabled(settings.getOrDefault(event,false));item.setEditable(true);
        }}
        return result;
    }

    private Integer resolveTemplateTypeForRead(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return null;
        }

        final int type;
        try {
            type = Integer.parseInt(rawType);
        } catch (NumberFormatException e) {
            throw new BusinessException("server.translate.template.invalid");
        }
        if (type <= 0) {
            throw new BusinessException("server.translate.template.invalid");
        }

        Translation template = getById(type);
        if (template == null || !Integer.valueOf(0).equals(template.getType())) {
            throw new BusinessException("server.translate.template.notFound");
        }
        return type;
    }

    /**
     * 从数据库查询单条翻译。
     *
     * 使用 LIMIT 1 防止历史脏数据导致 selectOne 报错。
     */
    private Translation fetchTranslationFromDB(String key, String lang, Integer type) {
        return repository.findLatestByKeyLanguageType(key,localeResolver.normalize(lang),type);
    }

    /**
     * Object 转字符串，空值转空字符串。
     */
    private String trimToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
    @Override
    public Translation getById(java.io.Serializable id) { return repository.getById(id); }
    @Override
    public boolean save(Translation record) { return repository.save(record); }
    @Override
    public boolean updateById(Translation record) { return repository.updateById(record); }
    @Override
    public boolean removeById(java.io.Serializable id) { return repository.removeById(id); }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveBatch(java.util.Collection<Translation> records) { return repository.saveBatch(records); }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateBatchById(java.util.Collection<Translation> records) { return repository.updateBatchById(records); }
}
