package org.token.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.token.custom.OperationLogSanitizer;
import org.token.exception.BusinessException;
import org.token.model.entity.OperationLog;
import org.token.model.request.FilterParams;
import org.token.model.request.QueryParams;
import org.token.service.OperationLogService;
import org.token.service.SystemSettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 操作日志服务实现
 */
@Service
public class OperationLogServiceImpl
        implements OperationLogService {
    private final org.token.repository.system.OperationLogRepository repository;

    private final SystemSettingService settings;

    @org.springframework.beans.factory.annotation.Autowired
    public OperationLogServiceImpl(SystemSettingService settings,org.token.repository.system.OperationLogRepository repository) {
        this.settings=settings;this.repository=repository;
    }

    public OperationLogServiceImpl(SystemSettingService settings) { this(settings,null); }

    /**
     * 新增操作日志。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void insert(OperationLog record) {
        if (record == null) {
            return;
        }

        OperationLogSanitizer.sanitize(record);
        repository.save(record);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<String> actionTypes() {
        return repository.actionTypes().stream().filter(StringUtils::hasText).toList();
    }

    /**
     * 后台分页查询操作日志。
     *
     * 支持：
     * 1. 日期范围
     * 2. 状态
     * 3. 日志类型
     * 4. 备注搜索
     * 5. 用户名精确搜索
     * 6. 排序
     */
    @Override
    @Transactional(readOnly = true)
    public IPage<OperationLog> getAdmin(QueryParams params) {
        FilterParams filterParam = params == null ? null : params.getFilterParams();
        LocalDateTime[] range = dateRange(filterParam);
        validateFilters(filterParam);

        long current = 1L;
        long size = 10L;

        if (params != null && params.getPage() != null) {
            Integer queryCurrent = params.getPage().getCurrent();
            Integer querySize = params.getPage().getSize();

            if (queryCurrent != null && queryCurrent > 0) {
                current = queryCurrent;
            }

            if (querySize != null && querySize > 0) {
                size = querySize;
            }
        }

        /*
         * 防止前端传超大 size，导致一次性查太多日志。
         */
        size = Math.min(size, 200L);

        IPage<OperationLog> page = repository.findPage(current,size,params,range[0],range[1]);
        page.getRecords().forEach(OperationLogSanitizer::sanitize);
        return page;
    }

    /**
     * 日期范围筛选。
     */
    private LocalDateTime[] dateRange(FilterParams filterParam) {
        if (filterParam == null) {
            return new LocalDateTime[] {null,null};
        }

        String[] dateRangeVal = filterParam.getDateRangeVal();

        if (dateRangeVal == null || dateRangeVal.length == 0) {
            return new LocalDateTime[] {null,null};
        }
        if (dateRangeVal.length != 2) throw new BusinessException("server.param.error");

        String startText = dateRangeVal[0];
        String endText = dateRangeVal[1];

        if (!StringUtils.hasText(startText) || !StringUtils.hasText(endText)) {
            throw new BusinessException("server.param.error");
        }

        try {
            LocalDate startDate = LocalDate.parse(startText.trim());
            LocalDate endDate = LocalDate.parse(endText.trim());
            if (endDate.isBefore(startDate)) throw new BusinessException("server.param.error");
            ZoneId zone = ZoneId.of(settings.resolveSystemTimeZone());
            LocalDateTime start = LocalDateTime.ofInstant(startDate.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
            LocalDateTime end = LocalDateTime.ofInstant(endDate.plusDays(1).atStartOfDay(zone).toInstant(), ZoneOffset.UTC);

            return new LocalDateTime[] {start,end};
        } catch (DateTimeException invalid) {
            throw new BusinessException("server.param.error");
        }
    }

    /**
     * 普通筛选条件。
     */
    private void validateFilters(FilterParams filterParam) {
        if (filterParam == null) {
            return;
        }

        String state = trimToEmpty(filterParam.getState());
        String type = trimToEmpty(filterParam.getType());
        String searchInputValue = trimToEmpty(filterParam.getSearchInputValue());
        String username = trimToEmpty(filterParam.getUserName());
        if ((!state.isEmpty() && !state.equals("0") && !state.equals("1"))
                || type.length() > 100 || username.length() > 50 || searchInputValue.length() > 200) {
            throw new BusinessException("server.param.error");
        }

    }

    /**
     * 排序。
     *
     * 不直接信任前端传入的 prop，
     * 避免字符串字段排序带来的 SQL 注入风险。
     */
    private String trimToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
