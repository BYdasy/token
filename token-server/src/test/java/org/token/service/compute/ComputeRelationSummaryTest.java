package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.model.query.MemberScope;
import org.token.repository.compute.ComputeMemberRelationRepository;
import org.token.repository.dao.compute.ComputeMemberRelationMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 核心关系摘要读取现有邀请事实，不修改树、不丢失异常。 */
class ComputeRelationSummaryTest {
    private JdbcTemplate jdbc;
    private ComputeMemberRelations relations;

    @BeforeEach void setup() {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:relations_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,username VARCHAR(40),inviter_id BIGINT,enabled INT,is_test INT)");
        jdbc.update("INSERT INTO compute_member VALUES(1,'A',NULL,1,0),(2,'B',1,0,1),(3,'C',2,1,0),(4,'D',1,1,0),(5,'X',NULL,1,0)");
        relations=new ComputeMemberRelations(jdbc);
    }

    @Test void coreSummaryRetainsDisabledAndTestIntermediateNodes() {
        var result=relations.summaries(List.of(1L,2L,3L,4L,3L));
        assertEquals(4,result.size());
        assertEquals(new ComputeMemberRelations.Summary(1,1,"A",0,true,2),result.get(1L));
        assertEquals(new ComputeMemberRelations.Summary(2,1,"A",1,true,1),result.get(2L));
        assertEquals(new ComputeMemberRelations.Summary(3,1,"A",2,false,0),result.get(3L));
        assertEquals(1,result.get(4L).depth());
    }

    @Test void movingParentChangesAllDescendantSummariesWithoutDuplicateRelationStorage() {
        jdbc.update("UPDATE compute_member SET inviter_id=5 WHERE id=2");
        var result=relations.summaries(List.of(2L,3L,4L));
        assertEquals(5,result.get(3L).topMemberId());
        assertEquals(2,result.get(3L).depth());
        assertEquals(1,result.get(4L).topMemberId());
    }

    @Test void directCountsUseOneBatchAndDoNotIncludeGrandchildrenOrOutsideScope() {
        var mapper=org.mockito.Mockito.spy(AnnotationMapperFactory.mapper(jdbc,ComputeMemberRelationMapper.class));
        var batched=new ComputeMemberRelations(new ComputeMemberRelationRepository(mapper));
        var scope=new MemberScope(false,List.of(1L));
        var result=batched.summaries(List.of(1L,2L,3L,4L,1L),scope);
        assertEquals(2,result.get(1L).directCount());
        assertEquals(1,result.get(2L).directCount());
        assertEquals(0,result.get(3L).directCount());
        org.mockito.Mockito.verify(mapper).directCounts(List.of(1L,2L,3L,4L),scope);
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.times(1)).directCounts(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any());
        assertEquals(0,batched.summaries(List.of(1L),new MemberScope(false,List.of(5L))).get(1L).directCount());
    }

    @Test void directCountsDoNotExposeChildrenBeyondAuthorizedDepthBoundary() {
        for(long id=10;id<=211;id++)jdbc.update("INSERT INTO compute_member VALUES(?,?,?,1,0)",id,"m"+id,id==10?null:id-1);
        assertEquals(1,relations.summaries(List.of(210L)).get(210L).directCount());
        var limited=relations.summaries(List.of(210L),new MemberScope(false,List.of(10L))).get(210L);
        assertEquals(0,limited.directCount());assertFalse(limited.hasChildren());
    }

    @Test void missingParentsAndCyclesDoNotSilentlyBecomeTopLevelUsers() {
        jdbc.update("UPDATE compute_member SET inviter_id=99 WHERE id=2");
        assertThrows(BusinessException.class,()->relations.summaries(List.of(3L)));
        jdbc.update("UPDATE compute_member SET inviter_id=3 WHERE id=2");
        var cycle=assertThrows(BusinessException.class,()->relations.summaries(List.of(2L,3L)));
        assertEquals("server.compute.relation.cycle",cycle.getMessage());
    }

    @Test void depthLimitIsCheckedEvenWhenEveryNodeIsAlreadyInRequestedPage() {
        for(long id=10;id<=211;id++) jdbc.update("INSERT INTO compute_member VALUES(?,?,?,1,0)",id,"m"+id,id==10?null:id-1);
        var ids=java.util.stream.LongStream.rangeClosed(10,211).boxed().toList();
        var deep=assertThrows(BusinessException.class,()->relations.summaries(ids));
        assertEquals("server.compute.relation.depthLimit",deep.getMessage());
        assertEquals(200,relations.summaries(List.of(210L)).get(210L).depth());
    }
}
