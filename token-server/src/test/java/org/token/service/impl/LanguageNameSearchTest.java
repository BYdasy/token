package org.token.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.token.config.EnabledLocaleResolver;
import org.token.model.entity.Language;
import org.token.model.request.FilterParams;
import org.token.model.request.QueryParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** 在隔离H2执行实际筛选SQL，验证三个语言名称列的包含匹配及通配符转义。 */
class LanguageNameSearchTest {
    JdbcTemplate jdbc;
    LanguageServiceImpl service;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:language_name_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE translate_language(id BIGINT PRIMARY KEY,lang VARCHAR(40),name VARCHAR(100),chinese_name VARCHAR(100),native_name VARCHAR(100),status INT,sort_no INT)");
        jdbc.update("INSERT INTO translate_language VALUES(1,'en','English','英语','English',1,2),(2,'fr','French','法语','Français',1,1),(3,'xx','Sample_100%','样本','Sample_100%',0,3)");
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "language-search"), Language.class);
        service = new LanguageServiceImpl(mock(EnabledLocaleResolver.class));
        var repository=new org.token.repository.system.LanguageRepository(mock(org.token.repository.dao.LanguageMapper.class)) {
            @Override public com.baomidou.mybatisplus.extension.plugins.pagination.Page<Language> findPage(long current,long size,QueryParams params,String pattern) {
                var values=new java.util.HashMap<String,Object>();values.put("query",params);values.put("namePattern",pattern);
                String statement=org.token.repository.dao.provider.SystemPageSql.languages(values);
                var args=new ArrayList<Object>();
                int count=statement.split(java.util.regex.Pattern.quote("#{namePattern}"),-1).length-1;
                for(int i=0;i<count;i++)args.add(pattern);
                statement=statement.replace("#{namePattern}","?");
                var rows=jdbc.query(statement,(row,index)->{
                    var language=new Language();language.setId(row.getLong("id"));language.setLang(row.getString("lang"));return language;
                },args.toArray());
                var page=new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Language>(current,size);
                page.setRecords(rows);page.setTotal(rows.size());return page;
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(service,"repository",repository);
    }

    List<String> search(String name) {
        var query = new QueryParams();var filter = new FilterParams();filter.setName(name);query.setFilterParams(filter);
        return service.pageLanguages(query).getRecords().stream().map(Language::getLang).toList();
    }

    @Test void findsPartsOfChineseEnglishAndNativeNamesIgnoringEnglishCase() {
        assertEquals(List.of("en"), search("ENG"));
        assertEquals(List.of("fr", "en"), search("语"));
        assertEquals(List.of("fr"), search("anç"));
        assertEquals(List.of("fr", "en", "xx"), search(""));
    }

    @Test void wildcardsAndSqlFragmentsRemainLiteralSearchText() {
        assertEquals(List.of("xx"), search("_100%"));
        assertEquals(List.of("xx"), search("%"));
        assertEquals(List.of(), search("' OR 1=1 --"));
    }
}
