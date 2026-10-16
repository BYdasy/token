package org.token.service.impl;

import org.token.repository.system.TranslationRepository;
import org.token.model.entity.Translation;
import org.token.model.response.TranslationCompletionOverview;
import org.token.model.response.TranslationCompletionProgress;
import org.token.model.response.TranslationCompletionResult;
import org.token.exception.BusinessException;
import org.token.service.LanguageService;
import org.token.service.TranslationCompletionService;
import org.token.tools.media.SundryUtils;
import org.token.tools.translation.TranslationUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 模板级翻译完整度与缺失语言补齐。
 *
 * 补齐可能调用外部翻译服务较长时间，因此不持有一个覆盖整个任务的数据库
 * 事务。它采用“模板级应用锁 + INSERT ... WHERE NOT EXISTS”，使任务可重试、
 * 不覆盖已有内容，并避免同一实例内的并发重复补齐。
 */
@Service
public class TranslationCompletionServiceImpl implements TranslationCompletionService {

    private final TranslationRepository translationRepository;

    private final LanguageService languageService;

    private final TranslationUtils translationUtils;

    private final org.token.tools.cache.TranslationBodyCache bodyCache;

    private final ConcurrentMap<Integer, ReentrantLock> templateLocks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TranslationCompletionServiceImpl(TranslationRepository translationRepository, LanguageService languageService,
                                            TranslationUtils translationUtils, org.token.tools.cache.TranslationBodyCache bodyCache) {
        this.translationRepository = translationRepository;
        this.languageService = languageService;
        this.translationUtils = translationUtils;
        this.bodyCache = bodyCache;
    }

    public TranslationCompletionServiceImpl(TranslationRepository translationRepository, LanguageService languageService,
                                            TranslationUtils translationUtils) {
        this(translationRepository, languageService, translationUtils, null);
    }

    @Override
    @Transactional(readOnly = true)
    public TranslationCompletionOverview overview(Integer type) {
        return loadSnapshot(requireTemplate(type)).overview();
    }

    @Override
    public TranslationCompletionResult fillMissing(Integer type) throws InterruptedException {
        return fillMissing(type, ignored -> { });
    }

    @Override
    public TranslationCompletionResult fillMissing(
            Integer type,
            Consumer<TranslationCompletionProgress> progressConsumer
    ) throws InterruptedException {
        Translation template = requireTemplate(type);
        ReentrantLock lock = templateLocks.computeIfAbsent(type, ignored -> new ReentrantLock());
        lock.lockInterruptibly();

        try {
            CompletionSnapshot before = loadSnapshot(template);
            TranslationCompletionOverview initialOverview = before.overview();
            Consumer<TranslationCompletionProgress> progress = progressConsumer == null
                    ? ignored -> { } : progressConsumer;
            int inserted = 0;
            int translatedKeys = 0;
            int processedKeys = 0;
            int totalKeys = before.recordsByKey().size();
            progress.accept(new TranslationCompletionProgress(
                    0, totalKeys, 0, 0, initialOverview.missingCount()));

            for (Map.Entry<String, Map<String, Translation>> keyEntry : before.recordsByKey().entrySet()) {
                try {
                    Map<String, Translation> recordsByLanguage = keyEntry.getValue();
                    List<String> missingLanguages = before.enabledLanguages().stream()
                            .filter(lang -> !recordsByLanguage.containsKey(lang))
                            .toList();
                    if (missingLanguages.isEmpty()) {
                        continue;
                    }

                    Translation source = selectSource(recordsByLanguage);
                    if (source == null || !StringUtils.hasText(source.getTvalue())) {
                        continue;
                    }

                    Map<String, String> translated = translationUtils.translateToMultipleLanguagesParallel(
                            source.getTvalue(),
                            missingLanguages,
                            resolveFormat(source.getProp())
                    );

                    int insertedForKey = 0;
                    LocalDateTime actionTime = SundryUtils.getTime();
                    for (String language : missingLanguages) {
                        String value = translated.get(language);
                        if (!StringUtils.hasText(value)) {
                            continue;
                        }

                        Translation item = copyForLanguage(source, type, language, value, actionTime);
                        insertedForKey += bodyCache == null ? translationRepository.insertIfMissing(item)
                                : bodyCache.writeTranslation(type, () -> translationRepository.insertIfMissing(item));
                    }

                    if (insertedForKey > 0) {
                        translatedKeys++;
                        inserted += insertedForKey;
                    }
                } finally {
                    processedKeys++;
                    progress.accept(new TranslationCompletionProgress(
                            processedKeys, totalKeys, inserted, translatedKeys,
                            initialOverview.missingCount()));
                }
            }

            CompletionSnapshot after = loadSnapshot(template);
            TranslationCompletionOverview finalOverview = after.overview();
            return new TranslationCompletionResult(
                    finalOverview.type(),
                    finalOverview.templateName(),
                    finalOverview.enabledLanguageCount(),
                    finalOverview.keyCount(),
                    finalOverview.expectedCount(),
                    finalOverview.existingCount(),
                    finalOverview.missingCount(),
                    finalOverview.completeKeyCount(),
                    finalOverview.incompleteKeyCount(),
                    inserted,
                    translatedKeys,
                    initialOverview.missingCount(),
                    finalOverview.missingCount()
            );
        } finally {
            lock.unlock();
        }
    }

    private Translation requireTemplate(Integer type) {
        if (type == null || type <= 0) {
            throw new BusinessException("server.translate.template.required");
        }

        Translation template = translationRepository.getById(type);
        if (template == null || !Integer.valueOf(0).equals(template.getType())) {
            throw new BusinessException("server.translate.template.notFound");
        }
        return template;
    }

    private CompletionSnapshot loadSnapshot(Translation template) {
        List<String> enabledLanguages = normalizeLanguages(languageService.enabledLanguageCodes());
        List<Translation> records = translationRepository.findByTypeLatestFirst(template.getId());

        Map<String, Map<String, Translation>> recordsByKey = new LinkedHashMap<>();
        if (records != null) {
            for (Translation record : records) {
                if (record == null || !StringUtils.hasText(record.getTkey())
                        || !StringUtils.hasText(record.getLang())) {
                    continue;
                }
                String key = record.getTkey().trim();
                String language = normalizeLanguage(record.getLang());
                if (!StringUtils.hasText(language)) {
                    continue;
                }
                recordsByKey
                        .computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(language, record);
            }
        }

        return new CompletionSnapshot(
                template.getId(),
                template.getTkey(),
                enabledLanguages,
                recordsByKey
        );
    }

    private Translation selectSource(Map<String, Translation> recordsByLanguage) {
        Translation chinese = recordsByLanguage.get("zh");
        if (chinese != null && StringUtils.hasText(chinese.getTvalue())) {
            return chinese;
        }

        for (Translation record : recordsByLanguage.values()) {
            if (record != null && StringUtils.hasText(record.getTvalue())) {
                return record;
            }
        }
        return null;
    }

    private Translation copyForLanguage(
            Translation source,
            Integer type,
            String language,
            String value,
            LocalDateTime actionTime
    ) {
        Translation item = new Translation();
        item.setTkey(source.getTkey().trim());
        item.setLang(language);
        item.setTvalue(value);
        item.setType(type);
        item.setProp(source.getProp() == null ? 0 : source.getProp());
        item.setActionTime(actionTime);
        item.setTitle(source.getTitle());
        item.setIsDel(source.getIsDel() == null ? 0 : source.getIsDel());
        return item;
    }

    private List<String> normalizeLanguages(List<String> languages) {
        if (languages == null || languages.isEmpty()) {
            return List.of();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String language : languages) {
            String value = normalizeLanguage(language);
            if (StringUtils.hasText(value)) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    private String normalizeLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return "";
        }
        return language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private String resolveFormat(Integer prop) {
        if (prop != null && prop == 2) {
            return "telegram";
        }
        return prop != null && prop == 1 ? "html" : "text";
    }

    private record CompletionSnapshot(
            Integer type,
            String templateName,
            List<String> enabledLanguages,
            Map<String, Map<String, Translation>> recordsByKey
    ) {

        private TranslationCompletionOverview overview() {
            int enabledLanguageCount = enabledLanguages.size();
            int keyCount = recordsByKey.size();
            long existingCount = 0;
            int completeKeyCount = 0;

            for (Map<String, Translation> recordsByLanguage : recordsByKey.values()) {
                long available = enabledLanguages.stream()
                        .filter(recordsByLanguage::containsKey)
                        .count();
                existingCount += available;
                if (available == enabledLanguageCount) {
                    completeKeyCount++;
                }
            }

            long expectedCount = (long) keyCount * enabledLanguageCount;
            long missingCount = Math.max(0, expectedCount - existingCount);
            return new TranslationCompletionOverview(
                    type,
                    templateName,
                    enabledLanguageCount,
                    keyCount,
                    expectedCount,
                    existingCount,
                    missingCount,
                    completeKeyCount,
                    keyCount - completeKeyCount
            );
        }
    }
}
