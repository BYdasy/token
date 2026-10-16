package org.token.service.compute;

import org.token.model.query.MemberScope;
import org.token.repository.support.NotificationFixture;
import org.token.service.NotificationPushService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComputeFinanceEventServiceTest {
    @Test void firstPacketAndChangedScopeAreSilentWhileAllOrderStatesAreStableEvents(){
        var fixture=new NotificationFixture();var scope=scope();var push=mock(NotificationPushService.class);
        fixture.jdbc.update("INSERT INTO compute_member(id) VALUES(1),(2)");
        var service=new ComputeFinanceEventService(fixture.repository,scope,push,fixture.manager);
        fixture.jdbc.update("INSERT INTO compute_deposit(id,member_id,order_no,status,created_at) VALUES(10,1,'D10','APPROVED',CURRENT_TIMESTAMP),(20,2,'D20','FAILED',CURRENT_TIMESTAMP)");
        service.collect();var first=service.events(false,null,null,200);assertEquals(List.of(),first.get("records"));
        fixture.jdbc.update("INSERT INTO compute_deposit(id,member_id,order_no,status,created_at) VALUES(30,1,'D30','REJECTED',CURRENT_TIMESTAMP),(40,2,'D40','PENDING',CURRENT_TIMESTAMP)");
        service.collect();var next=service.events(false,((Number)first.get("cursor")).longValue(),(String)first.get("scopeVersion"),200);
        assertEquals(List.of("deposit:30"),records(next).stream().map(row->row.get("eventKey")).toList());
        fixture.jdbc.update("UPDATE compute_deposit SET status='APPROVED' WHERE id=30");service.collect();
        assertTrue(records(service.events(false,((Number)next.get("cursor")).longValue(),(String)next.get("scopeVersion"),200)).isEmpty());
        when(scope.queryScope()).thenReturn(new MemberScope(false,List.of(2L)));
        var changed=service.events(false,0L,(String)first.get("scopeVersion"),200);
        assertTrue(records(changed).isEmpty());assertNotEquals(first.get("scopeVersion"),changed.get("scopeVersion"));
        verify(push,times(2)).financeOrders();
    }
    @Test void lateCommittedLowerBusinessIdGetsLaterEventCursor() throws Exception {
        var fixture=new NotificationFixture();var scope=scope();var service=new ComputeFinanceEventService(fixture.repository,scope,mock(NotificationPushService.class),fixture.manager);
        fixture.jdbc.update("INSERT INTO compute_member(id) VALUES(1)");
        var started=service.events(false,null,null,200);
        var pending=new java.util.concurrent.CountDownLatch(1);var commit=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var writer=executor.submit(()->new TransactionTemplate(fixture.manager).executeWithoutResult(status->{
                fixture.jdbc.update("INSERT INTO compute_deposit(id,member_id,order_no,status,created_at) VALUES(1,1,'D1','PENDING',CURRENT_TIMESTAMP)");pending.countDown();
                try{assertTrue(commit.await(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new RuntimeException(interrupted);}
            }));
            assertTrue(pending.await(5,java.util.concurrent.TimeUnit.SECONDS));
            fixture.jdbc.update("INSERT INTO compute_deposit(id,member_id,order_no,status,created_at) VALUES(2,1,'D2','PENDING',CURRENT_TIMESTAMP)");
            service.collect();var baseline=service.events(false,((Number)started.get("cursor")).longValue(),(String)started.get("scopeVersion"),200);
            commit.countDown();writer.get(5,java.util.concurrent.TimeUnit.SECONDS);service.collect();
            var result=service.events(false,((Number)baseline.get("cursor")).longValue(),(String)baseline.get("scopeVersion"),200);
            assertEquals(List.of("deposit:1"),records(result).stream().map(row->row.get("eventKey")).toList());
        }finally{commit.countDown();executor.shutdownNow();}
    }
    private static ComputeAgentScope scope(){var scope=mock(ComputeAgentScope.class);var relations=mock(ComputeMemberRelations.class);when(scope.relations()).thenReturn(relations);when(scope.currentAdminUid()).thenReturn(7);when(scope.queryScope()).thenReturn(new MemberScope(false,List.of(1L)));return scope;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> records(Map<String,Object> result){return (List<Map<String,Object>>)result.get("records");}
}
