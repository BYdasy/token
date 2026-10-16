package org.token.service.compute;

import lombok.RequiredArgsConstructor;
import org.token.config.EnabledLocaleResolver;
import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeVipRepository;
import org.token.service.compute.ComputeBusinessTranslations;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 用户身份摘要只读现有等级译文；批量解析避免列表逐行查库，也不重复创建翻译任务。 */
@Service
public class ComputeLevelNames {
    private final ComputeData data;
    private final ComputeVipRepository repository;
    private final EnabledLocaleResolver locales;

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeLevelNames(ComputeData data,ComputeVipRepository repository,EnabledLocaleResolver locales) {
        this.data=data;this.repository=repository;this.locales=locales;
    }
    public ComputeLevelNames(ComputeData data,EnabledLocaleResolver locales) { this(data,new ComputeVipRepository(data.jdbc()),locales); }

    public String name(Object level) {
        Map<String, Object> row = fields("level", level);
        decorate(List.of(row), "level", "levelName");
        return text(row.get("levelName"));
    }

    public void decorate(List<Map<String, Object>> records, String levelKey, String nameKey) {
        Set<Long> ids = new LinkedHashSet<>();
        records.forEach(row -> { if (row.get(levelKey) != null) ids.add(number(row.get(levelKey))); });
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            String language = locales.resolve(LocaleContextHolder.getLocale().toLanguageTag());
            for (Map<String, Object> level : repository.names(new ArrayList<>(ids))) {
                level.put("title", level.get("name"));
                level.put("bodyHtml", text(level.get("description")));
                Object raw = level.get("translationsJson");
                Map<String, Object> translations = raw == null || text(raw).isBlank() ? new LinkedHashMap<>() : data.jsonMap(raw);
                ComputeContentTranslationService.seedSource(level, translations);
                String name = "";
                for (String candidate : ComputeBusinessTranslations.candidates(language)) {
                    if (ComputeBusinessTranslations.complete(translations, candidate, text(level.get("description")))) {
                        name = text(ComputeBusinessTranslations.fields(translations, candidate).get("title"));
                        break;
                    }
                }
                names.put(number(level.get("id")), name);
            }
        }
        records.forEach(row -> row.put(nameKey, row.get(levelKey) == null ? "" : names.getOrDefault(number(row.get(levelKey)), "")));
    }
}
