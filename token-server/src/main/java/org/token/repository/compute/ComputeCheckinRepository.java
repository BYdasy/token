package org.token.repository.compute;

import java.util.*;
import org.token.model.query.MemberScope;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeCheckinMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeCheckinService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeCheckinRepository {
    private final ComputeCheckinMapper mapper;

    @Autowired
    public ComputeCheckinRepository(ComputeCheckinMapper mapper) { this.mapper = mapper; }

    public ComputeCheckinRepository(ComputeData data) {
        this(data.mapper(ComputeCheckinMapper.class));
    }

    public List<Map<String, Object>> statusCheckinRows(Object... args) {
        return ComputeData.normalizeRows(mapper.statusCheckinRows(args));
    }

    public Map<String, Object> checkinMemberRow(Object... args) {
        return RepositoryResults.required(mapper.checkinMemberRow(args));
    }

    public Integer checkinCheckinCount(Object... args) {
        return RepositoryResults.scalar(mapper.checkinCheckinCount(args), Integer.class);
    }

    public List<Map<String, Object>> checkinCheckinRows(Object... args) {
        return ComputeData.normalizeRows(mapper.checkinCheckinRows(args));
    }

    public Map<String,Object> records(MemberScope scope, String username, int page, int size) {
        long total=mapper.recordCount(scope,username);
        return ComputeData.fields("total",total,"records",ComputeData.normalizeRows(mapper.records(scope,username,size,(page-1)*size)));
    }
    public static final class Queries {
        private Queries() { }
        public static String count(Map<String,Object> p) { return query(p,true); }
        public static String records(Map<String,Object> p) { return query(p,false); }
        private static String query(Map<String,Object> p,boolean count) {
            var args=new ArrayList<Object>();
            String where=" WHERE 1=1"+MemberScopeQuery.memberFilter("c.member_id",(MemberScope)p.get("scope"),args);
            String username=(String)p.get("username");
            if(!username.isBlank()) {where+=" AND m.username=?";args.add(username);}
            String sql=(count?"SELECT COUNT(*)":"SELECT c.order_no,m.username,m.is_test,c.checkin_date,c.streak_days,c.reward_token,c.created_at")
                    +" FROM compute_checkin c JOIN compute_member m ON m.id=c.member_id"+where;
            if(!count){sql+=" ORDER BY c.id DESC LIMIT ? OFFSET ?";args.add(p.get("limit"));args.add(p.get("offset"));}
            return org.token.repository.support.SqlParameters.bind(sql,args,p);
        }
    }
}
