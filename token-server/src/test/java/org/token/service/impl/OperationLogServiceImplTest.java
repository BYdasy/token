package org.token.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.token.repository.dao.OperationLogMapper;
import org.token.model.entity.OperationLog;
import org.token.model.request.FilterParams;
import org.token.model.request.QueryParams;
import org.token.exception.BusinessException;
import org.token.service.OperationLogService;
import org.token.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OperationLogServiceImplTest {
    private OperationLogMapper mapper;
    private SystemSettingService settings;
    private OperationLogServiceImpl service;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "audit-test"), OperationLog.class);
        mapper = mock(OperationLogMapper.class);
        settings = mock(SystemSettingService.class);
        service = new OperationLogServiceImpl(settings);
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.OperationLogRepository(mapper));
    }

    @Test
    @SuppressWarnings("unchecked")
    void businessDayUsesExclusiveUtcEndIncludingDaylightSavingTransition() {
        when(settings.resolveSystemTimeZone()).thenReturn("America/New_York");
        when(mapper.selectPage(any(), any())).thenReturn(new Page<OperationLog>());
        service.getAdmin(params("2026-03-08", "2026-03-08"));
        ArgumentCaptor<LambdaQueryWrapper<OperationLog>> query=ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(),query.capture());
        String sql=query.getValue().getSqlSegment();
        assertTrue(sql.contains("actiontime >="));
        assertTrue(sql.contains("actiontime <"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(LocalDateTime.parse("2026-03-08T05:00:00")));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(LocalDateTime.parse("2026-03-09T04:00:00")));
    }

    @Test
    void invalidOrReversedDatesNeverFallBackToUnfilteredQuery() {
        for (String[] dates : List.of(new String[]{"2026-09-23", "2026-09-22"}, new String[]{"invalid", "2026-09-22"}, new String[]{"", "2026-09-22"}, new String[]{"2026-09-22"})) {
            var filter = new FilterParams();filter.setDateRangeVal(dates);
            var params = new QueryParams();params.setFilterParams(filter);
            assertEquals("server.param.error", assertThrows(BusinessException.class, () -> service.getAdmin(params)).getMessage());
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsUnknownStateAndOverlongSearch() {
        var filter = new FilterParams();filter.setState("2");
        var params = new QueryParams();params.setFilterParams(filter);
        assertThrows(BusinessException.class, () -> service.getAdmin(params));
        filter.setState("1");filter.setSearchInputValue("x".repeat(201));
        assertThrows(BusinessException.class, () -> service.getAdmin(params));
        verifyNoInteractions(mapper);
    }

    @Test
    void legacyCredentialsAreRedactedOnReadWithoutUpdatingHistory() {
        var row = new OperationLog();row.setRemarks("password=LEGACY_SECRET action=saved");
        var page = new Page<OperationLog>();page.setRecords(List.of(row));
        when(mapper.selectPage(any(), any())).thenReturn(page);
        assertFalse(service.getAdmin(null).getRecords().get(0).getRemarks().contains("LEGACY_SECRET"));
        verify(mapper, never()).updateById(any(OperationLog.class));
        verify(mapper, never()).insert(any(OperationLog.class));
    }

    @Test
    void actionOptionsKeepHistoricalExactNames() {
        when(mapper.actionTypes()).thenReturn(List.of("旧项目操作", "模板新增"));
        assertEquals(List.of("旧项目操作", "模板新增"), service.actionTypes());
    }

    @Test
    void insertSanitizesAndSurvivesBusinessRollbackInItsOwnTransaction() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:operation-audit-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE ad_record (remarks VARCHAR(4000), status INT)");
        doAnswer(call -> {
            OperationLog row = call.getArgument(0);
            return jdbc.update("INSERT INTO ad_record(remarks,status) VALUES(?,?)", row.getRemarks(), row.getStatus());
        }).when(mapper).insert(any(OperationLog.class));
        var manager = new DataSourceTransactionManager(source);
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        OperationLogService audited = (OperationLogService) proxy.getProxy();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var row = new OperationLog();row.setRemarks("token=PRIVATE_SECRET");row.setStatus(0);
            audited.insert(row);
            status.setRollbackOnly();
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ad_record", Integer.class));
        assertFalse(jdbc.queryForObject("SELECT remarks FROM ad_record", String.class).contains("PRIVATE_SECRET"));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM ad_record", Integer.class));
    }

    private QueryParams params(String start, String end) {
        var filter = new FilterParams();filter.setDateRangeVal(new String[]{start, end});
        var params = new QueryParams();params.setFilterParams(filter);
        return params;
    }
}
