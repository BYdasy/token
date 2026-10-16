package org.token.service.compute;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeContentRepository;
import org.token.repository.dao.compute.ComputeContentMapper;
import org.token.repository.dao.compute.ComputeContentQuerySql;
import org.token.repository.support.AnnotationMapperFactory;

import static org.junit.jupiter.api.Assertions.*;

/** 唯一会员消息入口只查询当前内容及接收关系，不依赖旧通知或旧代理结构。 */
class ComputeUnifiedMemberMessagesTest {
    private ComputeContentRepository repository;
    private final LocalDateTime now=LocalDateTime.of(2026,10,14,0,0);

    @BeforeEach void setup() {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:unified_messages_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE compute_content(id BIGINT PRIMARY KEY,kind VARCHAR(20),task_type VARCHAR(20),task_mode INT,title VARCHAR(200),body_html TEXT,deleted INT,enabled INT,status VARCHAR(20),starts_at TIMESTAMP,ends_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_content_recipient(content_id BIGINT,member_id BIGINT,read_at TIMESTAMP,completed_at TIMESTAMP,PRIMARY KEY(content_id,member_id))");
        jdbc.update("INSERT INTO compute_content(id,kind,title,body_html,deleted,enabled,status) VALUES(10,'MESSAGE','消息','<p>完整正文</p>',0,1,'PUBLISHED')");
        jdbc.update("INSERT INTO compute_content_recipient(content_id,member_id) VALUES(10,1)");
        repository=new ComputeContentRepository(AnnotationMapperFactory.mapper(jdbc,ComputeContentMapper.class));
    }

    @Test void onlyTheRecipientCanReadTheUnifiedMessage() {
        var rows=repository.userRows(10,1L,"MESSAGE","GENERAL",now,now);
        assertEquals(1,rows.size());
        assertEquals("<p>完整正文</p>",rows.get(0).get("bodyHtml"));
        assertTrue(repository.userRows(10,2L,"MESSAGE","GENERAL",now,now).isEmpty());
        assertTrue(repository.userRows(10,null,"MESSAGE","GENERAL",now,now).isEmpty());
    }

    @Test void agentAudienceRequiresAnActualMemberRoot() {
        Map<String,Object> parameters=new java.util.LinkedHashMap<>();
        parameters.put("mode","DELIVERY");
        parameters.put("content",Map.of("targetType","AGENT","processedId",0,"audienceMaxId",100));
        parameters.put("ids",List.of());
        assertThrows(BusinessException.class,()->ComputeContentQuerySql.select(parameters));
    }
}
