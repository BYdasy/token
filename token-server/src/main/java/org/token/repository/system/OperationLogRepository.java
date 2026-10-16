package org.token.repository.system;

import org.token.repository.dao.OperationLogMapper;
import org.token.model.entity.OperationLog;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.token.model.request.FilterParams;
import org.springframework.stereotype.Repository;
import java.util.*;

/** OperationLog 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class OperationLogRepository extends EntityRepository<OperationLog, OperationLogMapper> {
    public OperationLogRepository(OperationLogMapper mapper) { super(mapper); }

    public List<String> actionTypes() { return mapper.actionTypes(); }
    public Page<OperationLog> findPage(long current,long size,QueryParams query,java.time.LocalDateTime start,java.time.LocalDateTime end) {
        LambdaQueryWrapper<OperationLog> wrapper=new LambdaQueryWrapper<>();
        wrapper.ge(start!=null,OperationLog::getActionTime,start).lt(end!=null,OperationLog::getActionTime,end);
        FilterParams filter=query==null?null:query.getFilterParams();
        if(filter!=null) {
            String state=trim(filter.getState()),type=trim(filter.getType());
            String search=trim(filter.getSearchInputValue()),username=trim(filter.getUserName());
            wrapper.eq(!state.isEmpty(),OperationLog::getStatus,state)
                    .eq(!type.isEmpty(),OperationLog::getType,type)
                    .like(!search.isEmpty(),OperationLog::getRemarks,search)
                    .eq(!username.isEmpty(),OperationLog::getUsername,username);
        }
        String prop=query==null||query.getSortColumn()==null?"":trim(query.getSortColumn().getProp());
        boolean asc=query==null||query.getSortColumn()==null||!"descending".equalsIgnoreCase(trim(query.getSortColumn().getOrder()));
        switch(prop) {
            case "id" -> wrapper.orderBy(true,asc,OperationLog::getId);
            case "uid" -> wrapper.orderBy(true,asc,OperationLog::getUid);
            case "username" -> wrapper.orderBy(true,asc,OperationLog::getUsername);
            case "status" -> wrapper.orderBy(true,asc,OperationLog::getStatus);
            case "type" -> wrapper.orderBy(true,asc,OperationLog::getType);
            case "loginIp" -> wrapper.orderBy(true,asc,OperationLog::getLoginIp);
            case "clientos" -> wrapper.orderBy(true,asc,OperationLog::getClientos);
            case "domain" -> wrapper.orderBy(true,asc,OperationLog::getDomain);
            case "address" -> wrapper.orderBy(true,asc,OperationLog::getAddress);
            case "actionTime" -> wrapper.orderBy(true,asc,OperationLog::getActionTime);
            default -> wrapper.orderByDesc(OperationLog::getActionTime).orderByDesc(OperationLog::getId);
        }
        if(Set.of("uid","username","status","type","loginIp","clientos","domain","address","actionTime").contains(prop))wrapper.orderByDesc(OperationLog::getId);
        return mapper.selectPage(new Page<>(current,size),wrapper);
    }
    private static String trim(Object value) { return value==null?"":value.toString().trim(); }

}
