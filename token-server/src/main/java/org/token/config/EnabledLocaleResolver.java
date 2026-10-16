package org.token.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.token.repository.system.LanguageRepository;
import org.token.tools.cache.HybridCacheService;
import org.token.model.entity.Language;
import org.token.model.response.I18nLanguageOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.LocaleResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Duration;

/**
 * 根据后台已启用的语言配置解析请求语言。
 */
@Slf4j
@Component("localeResolver")
@RequiredArgsConstructor
public class EnabledLocaleResolver implements LocaleResolver {

    private static final String SOURCE_LANGUAGE = "en";

    private static final long CACHE_MILLIS = 30_000L;
    private static final String SHARED_CACHE_KEY = "i18n:enabled-languages";

    private final LanguageRepository languageRepository;

    @Value("${exp.i18n.default-language:zh}")
    private String defaultLanguage;

    private volatile List<I18nLanguageOption> cachedLanguages = List.of();

    private volatile long cacheExpiresAt;

    private HybridCacheService sharedCache;

    @Autowired(required = false)
    void setSharedCache(HybridCacheService sharedCache) {
        this.sharedCache = sharedCache;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        return Locale.forLanguageTag(resolve(request));
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        // 系统语言由请求头和后台启用配置共同决定，不在服务端会话中保存。
    }

    public String resolve(HttpServletRequest request) {
        return resolve(request == null ? null : request.getHeader("Accept-Language"));
    }

    public String resolve(String requestedLanguages) {
        List<I18nLanguageOption> enabled = enabledLanguages();
        List<String> requested = parseRequestedLanguages(requestedLanguages);

        for (String candidate : requested) {
            String matched = matchEnabled(candidate, enabled);
            if (StringUtils.hasText(matched)) {
                return matched;
            }
        }

        String configured = matchEnabled(normalize(defaultLanguage), enabled);
        if (requested.isEmpty() && StringUtils.hasText(configured)) {
            return configured;
        }

        String english = matchEnabled(SOURCE_LANGUAGE, enabled);
        if (StringUtils.hasText(english)) {
            return english;
        }

        return SOURCE_LANGUAGE;
    }

    public List<I18nLanguageOption> enabledLanguages() {
        long now = System.currentTimeMillis();
        List<I18nLanguageOption> snapshot = cachedLanguages;
        if (now < cacheExpiresAt && !snapshot.isEmpty()) {
            return snapshot;
        }

        synchronized (this) {
            if (now < cacheExpiresAt && !cachedLanguages.isEmpty()) {
                return cachedLanguages;
            }
            List<I18nLanguageOption> shared = readSharedCache();
            if (!shared.isEmpty()) {
                cachedLanguages = shared;
                cacheExpiresAt = now + CACHE_MILLIS;
                return shared;
            }
            try {
                List<Language> records = languageRepository.findOrdered(true);

                Map<String, I18nLanguageOption> unique = new LinkedHashMap<>();
                for (Language record : records) {
                    String value = normalize(record.getLang());
                    if (!StringUtils.hasText(value)) {
                        continue;
                    }
                    String label = StringUtils.hasText(record.getNativeName())
                            ? record.getNativeName().trim()
                            : StringUtils.hasText(record.getName()) ? record.getName().trim() : value;
                    unique.putIfAbsent(value, new I18nLanguageOption(label, value));
                }

                if (unique.isEmpty()) {
                    unique.put(SOURCE_LANGUAGE, new I18nLanguageOption("English", SOURCE_LANGUAGE));
                }
                cachedLanguages = List.copyOf(unique.values());
                cacheExpiresAt = now + CACHE_MILLIS;
                writeSharedCache(cachedLanguages);
                return cachedLanguages;
            } catch (Exception exception) {
                log.warn("读取启用语言配置失败，使用英语回退", exception);
                I18nLanguageOption english = new I18nLanguageOption("English", SOURCE_LANGUAGE);
                cachedLanguages = List.of(english);
                cacheExpiresAt = now + CACHE_MILLIS;
                return cachedLanguages;
            }
        }
    }

    public void invalidateCache() {
        cacheExpiresAt = 0L;
        cachedLanguages = List.of();
        if (sharedCache != null) {
            sharedCache.evict(SHARED_CACHE_KEY);
        }
    }

    public List<String> lookupCandidates(String language) {
        String exact = normalize(language);
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, exact);
        addCandidate(candidates, baseLanguage(exact));
        addCandidate(candidates, SOURCE_LANGUAGE);
        return candidates;
    }

    public String normalize(String language) {
        if (!StringUtils.hasText(language)) {
            return "";
        }
        String value = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return value.matches("^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$") ? value : "";
    }

    private List<String> parseRequestedLanguages(String header) {
        if (!StringUtils.hasText(header)) {
            return List.of();
        }
        try {
            return Locale.LanguageRange.parse(header).stream()
                    .map(Locale.LanguageRange::getRange)
                    .map(this::normalize)
                    .filter(StringUtils::hasText)
                    .toList();
        } catch (IllegalArgumentException exception) {
            String normalized = normalize(header);
            return StringUtils.hasText(normalized) ? List.of(normalized) : List.of();
        }
    }

    private String matchEnabled(String requested, List<I18nLanguageOption> enabled) {
        if (!StringUtils.hasText(requested)) {
            return "";
        }
        for (I18nLanguageOption option : enabled) {
            if (option.value().equals(requested)) {
                return option.value();
            }
        }

        String base = baseLanguage(requested);
        for (I18nLanguageOption option : enabled) {
            if (option.value().equals(base)) {
                return option.value();
            }
        }
        for (I18nLanguageOption option : enabled) {
            if (baseLanguage(option.value()).equals(base)) {
                return option.value();
            }
        }
        return "";
    }

    private String baseLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return "";
        }
        int separator = language.indexOf('-');
        return separator > 0 ? language.substring(0, separator) : language;
    }

    private void addCandidate(List<String> candidates, String value) {
        if (StringUtils.hasText(value) && !candidates.contains(value)) {
            candidates.add(value);
        }
    }

    private List<I18nLanguageOption> readSharedCache() {
        if (sharedCache == null) {
            return List.of();
        }
        String value = sharedCache.get(SHARED_CACHE_KEY);
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        List<I18nLanguageOption> options = new ArrayList<>();
        for (String line : value.split("\\n")) {
            String[] pair = line.split("\\t", 2);
            if (pair.length == 2 && StringUtils.hasText(pair[1])) {
                options.add(new I18nLanguageOption(pair[0], pair[1]));
            }
        }
        return List.copyOf(options);
    }

    private void writeSharedCache(List<I18nLanguageOption> options) {
        if (sharedCache == null || options == null || options.isEmpty()) {
            return;
        }
        String value = options.stream()
                .map(option -> option.label().replace("\t", " ").replace("\n", " ").replace("\r", " ")
                        + "\t" + option.value())
                .collect(java.util.stream.Collectors.joining("\n"));
        sharedCache.put(SHARED_CACHE_KEY, value, Duration.ofMinutes(5));
    }
}
