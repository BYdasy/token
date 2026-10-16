package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.controller.admin.ComputeAdminController;
import org.token.model.security.JwtUserPrincipal;
import org.token.custom.LogAction;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独立内存库：已提交订单可忽略/恢复，资金及外部任务不能被此操作更改。 */
class ComputeWithdrawalAttentionTest {
    JdbcTemplate jdbc;
    ComputeAgentScope scope;
    ComputeManagementService service;
    ComputeBusinessService business;
    @BeforeEach void setup() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:withdrawal_attention_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        source.setURL(source.getURL()+";DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,username VARCHAR(30),email VARCHAR(60),phone VARCHAR(30),level INT,group_id BIGINT)");
        jdbc.execute("CREATE TABLE compute_withdrawal(id BIGINT PRIMARY KEY,member_id BIGINT,status VARCHAR(30),amount DECIMAL(28,8),attention_ignored TINYINT DEFAULT 0,attention_updated_by INT,attention_updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO compute_member VALUES(1,'member','','',0,NULL)");
        jdbc.update("INSERT INTO compute_withdrawal(id,member_id,status,amount) VALUES(10,1,'PROCESSING',10)");
        scope=mock(ComputeAgentScope.class,RETURNS_DEEP_STUBS);business=mock(ComputeBusinessService.class);
        service=new ComputeManagementService(new FixtureComputeData(jdbc,new ObjectMapper()),jdbc,scope,mock(ComputeCatalogService.class),business);
    }
    @Test void submittedOrderCanBeIgnoredAndRestoredIdempotently() {
        service.setWithdrawalAttention(10,true,9);service.setWithdrawalAttention(10,true,9);
        assertEquals(1,jdbc.queryForObject("SELECT attention_ignored FROM compute_withdrawal",Integer.class));
        assertEquals(9,jdbc.queryForObject("SELECT attention_updated_by FROM compute_withdrawal",Integer.class));
        service.setWithdrawalAttention(10,false,9);
        assertEquals(0,jdbc.queryForObject("SELECT attention_ignored FROM compute_withdrawal",Integer.class));
        assertEquals("PROCESSING",jdbc.queryForObject("SELECT status FROM compute_withdrawal",String.class));
        assertEquals(0,new java.math.BigDecimal("10").compareTo(jdbc.queryForObject("SELECT amount FROM compute_withdrawal",java.math.BigDecimal.class)));
        verifyNoInteractions(business);
    }
    @Test void outOfScopeAndMissingOrdersCannotBeModified() {
        doThrow(ComputeAgentScope.denied()).when(scope).assertMember(1);
        assertThrows(org.token.exception.BusinessException.class,()->service.setWithdrawalAttention(10,true,9));
        assertThrows(org.token.exception.BusinessException.class,()->service.setWithdrawalAttention(999,true,9));
        assertEquals(0,jdbc.queryForObject("SELECT attention_ignored FROM compute_withdrawal",Integer.class));
    }
    @Test void mutationRequiresExistingReviewPermissionAndIsAudited() throws Exception {
        var method=ComputeAdminController.class.getMethod("withdrawalAttention",long.class,Map.class,JwtUserPrincipal.class);
        assertEquals("(hasAnyAuthority('610','61006','610062','super')) or (hasAnyAuthority('30201'))",method.getAnnotation(PreAuthorize.class).value());
        assertNotNull(method.getAnnotation(LogAction.class));
    }
}
