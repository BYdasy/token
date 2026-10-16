package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.config.EnabledLocaleResolver;
import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeVipRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 只读等级名称沿用精确、基础语言及英语回退，不把编号拼成用户可见名称。 */
class ComputeLevelNamesTest {
    private ComputeLevelNames names;
    private FixtureComputeData data;
    private ComputeVipRepository repository;

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:level_names_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE compute_level(id BIGINT PRIMARY KEY,name VARCHAR(80),description VARCHAR(200),source_language VARCHAR(20),translations_json VARCHAR(4000))");
        jdbc.update("INSERT INTO compute_level VALUES(1,'中文名称','说明','zh',?), (2,'English source','Description','en','{}'), (3,'仅中文','说明','zh','{}')",
                "{\"en\":{\"title\":\"Explorer\",\"bodyHtml\":\"Description\"},\"fr\":{\"title\":\"Explorateur\",\"bodyHtml\":\"Description\"},\"fr-CA\":{\"title\":\"Explorateur Canada\",\"bodyHtml\":\"Description\"}}");
        data = spy(new FixtureComputeData(jdbc, new ObjectMapper()));
        repository = spy(new ComputeVipRepository(jdbc));
        EnabledLocaleResolver resolver = mock(EnabledLocaleResolver.class);
        when(resolver.resolve(anyString())).thenAnswer(call -> call.getArgument(0));
        names = new ComputeLevelNames(data, repository, resolver);
    }

    @AfterEach void cleanup() { LocaleContextHolder.resetLocaleContext(); }

    @Test void exactAndBaseLanguageMatchConfiguredTitles() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("fr-CA"));
        assertEquals("Explorateur Canada", names.name(1));
        LocaleContextHolder.setLocale(Locale.forLanguageTag("fr-BE"));
        assertEquals("Explorateur", names.name(1));
    }

    @Test void missingLanguageUsesEnglishWithoutChineseOrNumberFallback() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
        assertEquals("Explorer", names.name(1));
        assertEquals("English source", names.name(2));
        assertEquals("", names.name(3));
        assertEquals("", names.name(99));
    }

    @Test void batchMembersReadDistinctLevelsInOneQuery() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        List<Map<String, Object>> members = List.of(new LinkedHashMap<>(Map.of("level", 1)),
                new LinkedHashMap<>(Map.of("level", 2)), new LinkedHashMap<>(Map.of("level", 1)));
        names.decorate(members, "level", "levelName");
        assertEquals(List.of("Explorer", "English source", "Explorer"), members.stream().map(row -> row.get("levelName")).toList());
        verify(repository, times(1)).names(List.of(1L, 2L));
    }
}
