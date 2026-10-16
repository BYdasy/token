package org.token.service.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.model.request.system.SessionMonitorSettingsRequest;
import org.token.repository.dao.system.LoginSessionSettingsMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.support.FixtureComputeData;
import org.token.repository.system.LoginSessionSettingsRepository;
import org.token.service.SystemSettingService;
import org.token.service.auth.LoginSessionPolicy;
import org.token.service.compute.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionMonitorSettingsServiceTest {
    JdbcTemplate jdbc;
    SessionMonitorSettingsService service;
    LoginSessionSettingsRepository sessionRows;
    boolean global = true;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:session_monitor_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE admin_login_session(token_hash VARCHAR(64),last_active_at TIMESTAMP(6),expires_at TIMESTAMP(6),absolute_expires_at TIMESTAMP(6),revoked_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE compute_member_session(token_hash VARCHAR(64),last_seen_at TIMESTAMP(6),expires_at TIMESTAMP(6))");
        var settings = mock(SystemSettingService.class);
        doAnswer(call -> {
            String key=call.getArgument(0), value=call.getArgument(1);
            jdbc.update("MERGE INTO system_setting(setting_key,setting_value,updated_by) KEY(setting_key) VALUES(?,?,?)", key, value, call.getArgument(2)); return null;
        })
                .when(settings).updateValue(anyString(),anyString(),anyInt());
        var policy = new LoginSessionPolicy(jdbc);
        sessionRows = spy(new LoginSessionSettingsRepository(AnnotationMapperFactory.mapper(jdbc,LoginSessionSettingsMapper.class)));
        var sessions = new LoginSessionSettingsService(policy,settings,sessionRows);
        var data = new FixtureComputeData(jdbc,new ObjectMapper());
        var scope = mock(ComputeAgentScope.class);
        when(scope.current()).thenAnswer(call -> new ComputeAgentScope.Scope(global,List.of(),true));
        doAnswer(call -> { if(!global) throw new BusinessException("server.compute.management.scopeDenied"); return null; }).when(scope).requireGlobalScope();
        var monitor = new ComputeLiveMonitorService(data,scope,new ComputeOnlinePolicy(data,policy));
        var proxy = new ProxyFactory(new SessionMonitorSettingsService(sessions,monitor));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        service = (SessionMonitorSettingsService)proxy.getProxy();
    }

    @Test void oneSaveCommitsBothKindsOfSettingsAndDoesNotReviveExpiredSessions() {
        var now=LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        jdbc.update("INSERT INTO compute_member_session VALUES('expired',?,?)",now.minusHours(80),now.minusHours(8));
        var result=service.save(new SessionMonitorSettingsRequest(48,96,30),1);
        assertEquals(48,result.get("adminIdleHours"));assertEquals(96,result.get("userIdleHours"));assertEquals(30,result.get("onlineWindowMinutes"));
        assertEquals(now.minusHours(8),jdbc.queryForObject("SELECT expires_at FROM compute_member_session",LocalDateTime.class));
    }
    @Test void lateSessionWriteFailureRollsBackEarlierMonitorAndPolicyWrites() {
        doThrow(new IllegalStateException("isolated fixture failure")).when(sessionRows).updateActiveExpiry(anyInt(),anyInt(),any(),any(),any());
        assertThrows(IllegalStateException.class,()->service.save(new SessionMonitorSettingsRequest(48,96,30),1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM system_setting",Integer.class));
    }
    @Test void scopedAdministratorKeepsSessionCapabilityWithoutGainingMonitorWrite() {
        global=false;
        assertThrows(BusinessException.class,()->service.save(new SessionMonitorSettingsRequest(48,96,30),1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM system_setting",Integer.class));
        var result=service.save(new SessionMonitorSettingsRequest(48,96,null),1);
        assertEquals(false,result.get("globalScope"));assertEquals(10,result.get("onlineWindowMinutes"));
        assertEquals(48,result.get("adminIdleHours"));assertEquals(96,result.get("userIdleHours"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM system_setting",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM system_setting WHERE setting_key=?",Integer.class,ComputeOnlinePolicy.SETTING_KEY));
    }
    @Test void invalidWindowCannotPartiallySaveSessionPolicy() {
        assertThrows(BusinessException.class,()->service.save(new SessionMonitorSettingsRequest(48,96,1441),1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM system_setting",Integer.class));
    }
}
