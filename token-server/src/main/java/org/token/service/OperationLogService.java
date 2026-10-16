package org.token.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.token.model.entity.OperationLog;
import org.token.model.request.QueryParams;

/**
 * 操作日志服务
 */
public interface OperationLogService {

    /**
     * 新增操作日志。
     */
    void insert(OperationLog record);

    /**
     * 后台分页查询操作日志。
     */
    IPage<OperationLog> getAdmin(QueryParams params);

    /** 已产生的历史动作名称，用于保留旧记录的筛选入口。 */
    java.util.List<String> actionTypes();
}
