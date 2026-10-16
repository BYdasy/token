package org.token.service.compute;

import org.junit.jupiter.api.Tag;

import org.token.exception.BusinessException;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeCredentials;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只使用隔离H2验证一次性消费、即时撤销、身份变化与真实代理范围。 */
@Tag("regression")
class ComputeMemberAccessServiceTest {
    JdbcTemplate jdbc; TransactionTemplate tx; ComputeMemberAccessService service; ComputeAccountService accounts;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:access_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setIsolationLevel(2);
        jdbc.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeWorkflowIntegrationTest.utcTimestamp'");
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,username VARCHAR(40),email VARCHAR(120),phone VARCHAR(24),level INT,invite_code VARCHAR(32),inviter_id BIGINT,enabled INT,password_hash VARCHAR(100),pay_password_hash VARCHAR(100),created_at DATETIME(6),user_time_zone VARCHAR(64))");
        jdbc.execute("CREATE TABLE compute_member_session(token_hash VARCHAR(64) PRIMARY KEY,member_id BIGINT,credential_hash VARCHAR(100),expires_at DATETIME(6),last_seen_at DATETIME(6))");
        jdbc.execute("CREATE TABLE ad_master(uid INT PRIMARY KEY,username VARCHAR(40),password VARCHAR(100),role INT,state INT,scope_configured_at DATETIME(6))");
        jdbc.execute("CREATE TABLE user_role(id INT PRIMARY KEY,role VARCHAR(200),isSelf INT)");
        jdbc.execute("CREATE TABLE compute_relation_state(singleton_id INT PRIMARY KEY,version BIGINT)");
        jdbc.execute("CREATE TABLE compute_admin_member_scope(admin_uid INT,root_member_id BIGINT)");
        TokenTestSchema.create(jdbc, "compute_member_login_ticket");
        jdbc.update("INSERT INTO compute_relation_state VALUES(1,1)");
        jdbc.update("INSERT INTO user_role VALUES(1,'',1),(2,'20105,20106',0)");
        jdbc.update("INSERT INTO ad_master VALUES(1,'admin','admin-hash',1,1,NULL),(2,'operator','operator-hash',2,1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_admin_member_scope VALUES(2,2)");
        for(int id=1;id<=4;id++)jdbc.update("INSERT INTO compute_member(id,username,level,invite_code,inviter_id,enabled,password_hash,created_at) VALUES(?,?,1,?,?,1,'member-hash',CURRENT_TIMESTAMP)",id,"member"+id,"invite"+id,id==1||id==4?null:id-1);
        ComputeAgentScope scope=new ComputeAgentScope(jdbc);
        accounts=new ComputeAccountService(jdbc,new PasswordUtils(),mock(ComputeBusinessService.class),scope);
        service=new ComputeMemberAccessService(jdbc,scope,accounts);login(2,"20105","20106");
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void login(int id,String...codes){var p=JwtUserPrincipal.builder().uid(id).username("operator").authorities(Set.of(codes)).build();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,Arrays.stream(codes).map(SimpleGrantedAuthority::new).toList()));}
    private String issue(long id){return tx.execute(status->service.issue(id)).get("ticket").toString();}
    private Map<String,Object> exchange(String ticket){return tx.execute(status->service.exchange(ticket));}
    @Test void ticketIsSingleUseAndForceLogoutRevokesAllSessionsAndUnspentTickets(){
        String ticket=issue(3);String token=exchange(ticket).get("token").toString();assertEquals(3,accounts.authenticate(token).id());
        assertThrows(BusinessException.class,()->exchange(ticket));String unused=issue(3);
        login(2,"20106");int revoked=tx.execute(status->service.forceLogout(3));assertEquals(1,revoked);assertNull(accounts.authenticate(token));assertThrows(BusinessException.class,()->exchange(unused));
    }
    @Test void disabledChangedPasswordExpiredAndRevokedPermissionTicketsFail(){
        String disabled=issue(3);jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=3");assertThrows(BusinessException.class,()->exchange(disabled));jdbc.update("UPDATE compute_member SET enabled=1 WHERE id=3");
        String changed=issue(3);jdbc.update("UPDATE ad_master SET password='new' WHERE uid=2");assertThrows(BusinessException.class,()->exchange(changed));
        String expired=issue(3);jdbc.update("UPDATE compute_member_login_ticket SET expires_at=?",LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));assertThrows(BusinessException.class,()->exchange(expired));
        String revoked=issue(3);jdbc.update("UPDATE user_role SET role='20106' WHERE id=2");assertThrows(BusinessException.class,()->exchange(revoked));
    }
    @Test void scopeIsRecheckedAndFullAncestorChainContainsOnlyRelationshipLabels(){
        assertThrows(BusinessException.class,()->tx.execute(status->service.issue(4)));
        var chain=service.ancestors(3);assertEquals(List.of("member2","member1"),chain.stream().map(row->row.get("username")).toList());assertEquals(Set.of("username","depth"),chain.get(1).keySet());
        String ticket=issue(3);jdbc.update("UPDATE compute_admin_member_scope SET root_member_id=4 WHERE admin_uid=2");assertThrows(BusinessException.class,()->exchange(ticket));
    }
    @Test void simultaneousExchangeIssuesAtMostOneSession() throws Exception {
        String ticket=issue(3);var executor=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)tasks.add(executor.submit(()->{start.await();try{exchange(ticket);return true;}catch(BusinessException expected){return false;}}));start.countDown();int success=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))success++;assertEquals(1,success);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session",Integer.class));}
        finally{executor.shutdownNow();}
    }
    @Test void impersonationReplacesExistingSessionAndOldLogoutCannotRevokeReplacement(){
        String original=tx.execute(status->accounts.issueSession(3,"member-hash"));
        String replacement=exchange(issue(3)).get("token").toString();
        assertNull(accounts.authenticate(original));
        accounts.recordSessionActivity(original);accounts.logout(original);
        assertNotNull(accounts.authenticate(replacement));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session WHERE member_id=3",Integer.class));
        String regular=tx.execute(status->accounts.issueSession(3,"member-hash"));
        assertNull(accounts.authenticate(replacement));assertNotNull(accounts.authenticate(regular));
    }
    @Test void concurrentDifferentTicketsStillLeaveOnlyOneUsableSession() throws Exception {
        String first=issue(3),second=issue(3);
        var executor=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            var one=executor.submit(()->{start.await();return exchange(first).get("token").toString();});
            var two=executor.submit(()->{start.await();return exchange(second).get("token").toString();});
            start.countDown();
            var tokens=List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
            assertEquals(1,tokens.stream().filter(token->accounts.authenticate(token)!=null).count());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session WHERE member_id=3",Integer.class));
        }finally{executor.shutdownNow();}
    }
    @Test void forceLogoutSerializesWithPendingSessionIssueAndCannotBeUndoneByLateActivity() throws Exception {
        var executor=Executors.newFixedThreadPool(2);
        CountDownLatch issued=new CountDownLatch(1),releaseLogin=new CountDownLatch(1),revokeStarted=new CountDownLatch(1);
        try {
            var pendingLogin=executor.submit(()->tx.execute(status->{
                String token=accounts.issueSession(3,"member-hash");issued.countDown();
                try {if(!releaseLogin.await(10,TimeUnit.SECONDS))throw new AssertionError("会话并发测试等待超时");}
                catch(InterruptedException exception){Thread.currentThread().interrupt();throw new AssertionError(exception);}
                return token;
            }));
            assertTrue(issued.await(10,TimeUnit.SECONDS));
            var revoke=executor.submit(()->{
                login(2,"20106");revokeStarted.countDown();
                try{return tx.execute(status->service.forceLogout(3));}
                finally{SecurityContextHolder.clearContext();}
            });
            assertTrue(revokeStarted.await(10,TimeUnit.SECONDS));releaseLogin.countDown();
            String token=pendingLogin.get(10,TimeUnit.SECONDS);
            assertEquals(1,revoke.get(10,TimeUnit.SECONDS));
            accounts.recordSessionActivity(token);
            assertNull(accounts.authenticate(token));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_member_session WHERE member_id=3",Integer.class));
        }finally{releaseLogin.countDown();executor.shutdownNow();}
    }
    @Test void sharedPasswordPolicyAcceptsSixAndRejectsFiveAndOversizedUtf8(){
        assertDoesNotThrow(()->ComputeCredentials.validatePassword("123456"));assertDoesNotThrow(()->new PasswordUtils().hashPassword("123456"));
        assertThrows(BusinessException.class,()->ComputeCredentials.validatePassword("12345"));assertThrows(BusinessException.class,()->new PasswordUtils().hashPassword("12345"));
        assertThrows(BusinessException.class,()->ComputeCredentials.validatePayPassword("中".repeat(25)));
    }
}
