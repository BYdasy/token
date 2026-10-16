package org.token.repository.compute;

import java.util.*;
import org.token.model.query.MemberScope;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeLiveMonitorMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeLiveMonitorService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeLiveMonitorRepository {
    private final ComputeLiveMonitorMapper mapper;

    @Autowired
    public ComputeLiveMonitorRepository(ComputeLiveMonitorMapper mapper) { this.mapper = mapper; }

    public ComputeLiveMonitorRepository(ComputeData data) {
        this(data.mapper(ComputeLiveMonitorMapper.class));
    }

    public int saveSettingWrite(Object... args) {
        return mapper.saveSettingWrite(args);
    }

    public Long onlineCount(LocalDateTime observedAt, LocalDateTime onlineSince, LocalDateTime idleSince, MemberScope scope) {
        return mapper.onlineCount(observedAt, onlineSince, idleSince, scope);
    }
    public Long pendingWithdrawals(MemberScope scope) { return mapper.pendingWithdrawals(scope); }
    public static final class Queries {
        private Queries() { }
        public static String online(Map<String,Object> parameters) {
            var args=new ArrayList<Object>();
            String where=ComputeOnlineRepository.predicate((LocalDateTime)parameters.get("observedAt"),(LocalDateTime)parameters.get("onlineSince"),(LocalDateTime)parameters.get("idleSince"),args)
                    + MemberScopeQuery.memberFilter("m.id",(MemberScope)parameters.get("scope"),args);
            return org.token.repository.support.SqlParameters.bind("SELECT COUNT(DISTINCT s.member_id) FROM compute_member_session s JOIN compute_member m ON m.id=s.member_id WHERE "+where,args,parameters);
        }
        public static String withdrawals(Map<String,Object> parameters) {
            var args=new ArrayList<Object>();
            String where=" WHERE w.status IN ('PENDING','PROCESSING') AND w.attention_ignored=0"+MemberScopeQuery.memberFilter("w.member_id",(MemberScope)parameters.get("scope"),args);
            return org.token.repository.support.SqlParameters.bind("SELECT COUNT(*) FROM compute_withdrawal w"+where,args,parameters);
        }
    }
}
