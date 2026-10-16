package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.auth.LoginSessionPolicy;
import org.token.controller.admin.ComputeLiveMonitorController;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 固定UTC时钟及H2验证活动窗口、有效会话、权限、实时代理范围；不使用真实账号和数据库。 */
class ComputeLiveMonitorTest {
    JdbcTemplate jdbc;
    ComputeOnlinePolicy online;
    ComputeLiveMonitorService monitor;
    final LocalDateTime observed=LocalDateTime.of(2026,9,22,8,0);

    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:live_monitor_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");

        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,inviter_id BIGINT,enabled INT,password_hash VARCHAR(100))");
        jdbc.execute("CREATE TABLE compute_member_session(token_hash VARCHAR(64) PRIMARY KEY,member_id BIGINT,credential_hash VARCHAR(100),expires_at TIMESTAMP(6),last_seen_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE compute_withdrawal(id BIGINT PRIMARY KEY,member_id BIGINT,status VARCHAR(32),attention_ignored TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE compute_admin_member_scope(admin_uid INT,root_member_id BIGINT)");
        jdbc.execute("CREATE TABLE ad_master(uid INT PRIMARY KEY,scope_configured_at DATETIME(6))");
        jdbc.update("INSERT INTO ad_master(uid) VALUES(9)");
        jdbc.update("INSERT INTO compute_member VALUES(1,NULL,1,'credential'),(2,1,1,'credential'),(3,NULL,1,'credential'),(4,NULL,0,'credential')");
        var data=new FixtureComputeData(jdbc,new ObjectMapper());
        online=new ComputeOnlinePolicy(data,new LoginSessionPolicy(jdbc),Clock.fixed(observed.toInstant(ZoneOffset.UTC),ZoneOffset.UTC));
        monitor=new ComputeLiveMonitorService(data,new ComputeAgentScope(jdbc),online);
        login("super");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void login(String...codes) {
        var principal=JwtUserPrincipal.builder().uid(9).username("monitor-test").authorities(Set.of(codes)).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,Arrays.stream(codes).map(SimpleGrantedAuthority::new).toList()));
    }
    void session(String token,long member,String hash,LocalDateTime active,LocalDateTime expiry) {
        jdbc.update("INSERT INTO compute_member_session VALUES(?,?,?,?,?)",token,member,hash,expiry,active);
    }
    void recent(String token,long member,int minutes) { session(token,member,"credential",observed.minusMinutes(minutes),observed.plusHours(2)); }

    @Test void defaultTenMinutesCountsDistinctValidEnabledMembersAtExactBoundary() {
        recent("one",1,8);recent("other-session",1,1);recent("boundary",2,10);
        session("old",3,"credential",observed.minusMinutes(10).minusSeconds(1),observed.plusHours(1));
        recent("disabled",4,0);
        session("expired",3,"credential",observed,observed);
        session("changed",3,"old-credential",observed,observed.plusHours(1));
        session("unobserved",3,"credential",null,observed.plusHours(1));
        assertEquals(10,monitor.status().get("onlineWindowMinutes"));
        assertEquals(2L,monitor.status().get("onlineCount"));
        assertEquals(Set.of(1L,2L),online.onlineMembers(List.of(1L,2L,3L,4L)));
        jdbc.update("DELETE FROM compute_member_session WHERE member_id=1");
        assertEquals(1L,monitor.status().get("onlineCount"),"撤销全部会话立即离线");
        jdbc.update("UPDATE compute_member SET password_hash='changed' WHERE id=2");
        assertEquals(0L,monitor.status().get("onlineCount"),"凭据变化立即离线");
    }

    @Test void windowChangesImmediatelyAndNeverOverridesSecurityIdleExpiry() {
        recent("eight-minutes",1,8);
        assertEquals(1L,monitor.status().get("onlineCount"));
        monitor.save(Map.of("onlineWindowMinutes",5));
        assertEquals(0L,monitor.status().get("onlineCount"));
        monitor.save(Map.of("onlineWindowMinutes",1440));
        session("two-hours",2,"credential",observed.minusHours(2),observed.plusHours(10));
        assertEquals(2L,monitor.status().get("onlineCount"));
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES(?,?)",LoginSessionPolicy.USER_KEY,"1");
        assertEquals(1L,monitor.status().get("onlineCount"));
        assertEquals(Set.of(1L),online.onlineMembers(List.of(1L,2L)));
    }

    @Test void scopedSnapshotCountsOnlyPendingAndProcessingWithinCurrentAgentTree() {
        recent("one",1,1);recent("two",2,1);recent("outside",3,1);
        jdbc.update("INSERT INTO compute_withdrawal(id,member_id,status) VALUES(1,1,'PENDING'),(2,2,'PROCESSING'),(3,1,'APPROVED'),(4,2,'REJECTED'),(5,1,'SUCCEEDED'),(6,2,'FAILED'),(7,3,'PENDING')");
        jdbc.update("INSERT INTO compute_admin_member_scope VALUES(9,1)");
        login("20100","30200");
        assertEquals(2L,monitor.status().get("onlineCount"));assertEquals(2L,monitor.status().get("pendingWithdrawalCount"));
        jdbc.update("UPDATE compute_admin_member_scope SET root_member_id=3 WHERE admin_uid=9");
        assertEquals(1L,monitor.status().get("onlineCount"));assertEquals(1L,monitor.status().get("pendingWithdrawalCount"));
        jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=3");
        assertEquals(0L,monitor.status().get("onlineCount"));assertEquals(0L,monitor.status().get("pendingWithdrawalCount"));
    }

    @Test void ignoredSubmittedWithdrawalStopsCountingButKeepsItsRealStatus() {
        jdbc.update("INSERT INTO compute_withdrawal(id,member_id,status) VALUES(1,1,'PROCESSING'),(2,2,'PENDING')");
        jdbc.update("UPDATE compute_withdrawal SET attention_ignored=1 WHERE id=1");
        assertEquals(1L,monitor.status().get("pendingWithdrawalCount"));
        assertEquals("PROCESSING",jdbc.queryForObject("SELECT status FROM compute_withdrawal WHERE id=1",String.class));
        jdbc.update("UPDATE compute_withdrawal SET attention_ignored=0 WHERE id=1");
        assertEquals(2L,monitor.status().get("pendingWithdrawalCount"));
    }

    @Test void missingPermissionReturnsNullAndKeepsExistingListCompatibility() {
        recent("one",1,1);jdbc.update("INSERT INTO compute_withdrawal(id,member_id,status) VALUES(1,1,'PENDING')");
        login("20100");assertEquals(1L,monitor.status().get("onlineCount"));assertNull(monitor.status().get("pendingWithdrawalCount"));
        login("30200");assertNull(monitor.status().get("onlineCount"));assertEquals(1L,monitor.status().get("pendingWithdrawalCount"));
        login("20102");assertEquals(1L,monitor.status().get("onlineCount"));assertNull(monitor.status().get("pendingWithdrawalCount"));
        login("30201");assertNull(monitor.status().get("onlineCount"));assertEquals(1L,monitor.status().get("pendingWithdrawalCount"));
        login("20100","30200");assertEquals(1L,monitor.status().get("onlineCount"));assertEquals(1L,monitor.status().get("pendingWithdrawalCount"));
        login("60101");assertNull(monitor.status().get("onlineCount"));assertNull(monitor.status().get("pendingWithdrawalCount"));
    }

    @Test void globalConfigurationValidatesRangeAndScopeWithoutOverwritingOnFailure() throws Exception {
        monitor.save(Map.of("onlineWindowMinutes",15));
        for(Object invalid:List.of(0,1441,-1,1.5,"10"))assertThrows(BusinessException.class,()->monitor.save(Map.of("onlineWindowMinutes",invalid)));
        assertThrows(BusinessException.class,()->monitor.save(Map.of("onlineWindowMinutes",10,"extra",true)));
        assertEquals(15,monitor.settings().get("onlineWindowMinutes"));
        jdbc.update("INSERT INTO compute_admin_member_scope VALUES(9,1)");login("60102");
        assertEquals(false,monitor.settings().get("globalScope"));assertEquals(15,monitor.settings().get("onlineWindowMinutes"));
        assertThrows(BusinessException.class,()->monitor.save(Map.of("onlineWindowMinutes",20)));
        String statusAuthority=ComputeLiveMonitorController.class.getMethod("status").getAnnotation(PreAuthorize.class).value();
        assertTrue(statusAuthority.contains("'20100'"));assertTrue(statusAuthority.contains("'30200'"));
        assertFalse(statusAuthority.contains("'610052'"));
        assertEquals("hasAnyAuthority('60102','super')",ComputeLiveMonitorController.class.getMethod("save",Map.class).getAnnotation(PreAuthorize.class).value());
    }
}
