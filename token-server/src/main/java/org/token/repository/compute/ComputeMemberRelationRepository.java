package org.token.repository.compute;

import org.token.exception.BusinessException;
import org.token.repository.dao.compute.ComputeMemberRelationMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.support.SqlParameters;
import org.token.model.query.MemberScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** 关系查询和范围条件仅允许服务端标识符，会员ID始终绑定参数。 */
@Repository
public class ComputeMemberRelationRepository {
    private final ComputeMemberRelationMapper mapper;
    @Autowired public ComputeMemberRelationRepository(ComputeMemberRelationMapper mapper){this.mapper=mapper;}
    public ComputeMemberRelationRepository(JdbcTemplate jdbc){this(AnnotationMapperFactory.mapper(jdbc,ComputeMemberRelationMapper.class));}
    public long lock(){return Objects.requireNonNull(mapper.lock());}
    public long version(){return Objects.requireNonNull(mapper.version());}
    public void changed(){mapper.changed();}
    public Map<String,Object> stats(long root){return mapper.stats(root);}
    public List<Map<String,Object>> ancestor(long id){
        var rows=ComputeData.normalizeRows(mapper.ancestor(id));
        rows.forEach(row->{
            row.put("id",((Number)row.get("id")).longValue());
            if(row.get("inviterId")!=null)row.put("inviterId",((Number)row.get("inviterId")).longValue());
            row.put("enabled",ComputeData.enabled(row.get("enabled")));
        });
        return rows;
    }
    public List<Map<String,Object>> nodes(List<Long> ids){return ids.isEmpty()?List.of():ComputeData.normalizeRows(mapper.nodes(ids));}
    public List<Map<String,Object>> directCounts(List<Long> ids,MemberScope scope){
        return ids.isEmpty()?List.of():ComputeData.normalizeRows(mapper.directCounts(ids,scope));
    }
    public boolean contains(long root,long memberId){return mapper.contains(root,memberId)==1;}

    public static class Queries {
        public static String contains(){return "SELECT COUNT(*) FROM compute_member m WHERE m.id=#{memberId}"+MemberScopeQuery.descendant("m.id",List.of("#{root}"),0,200,true);}
        public static String directCounts(Map<String,Object> parameters){
            @SuppressWarnings("unchecked") List<Long> ids=(List<Long>)parameters.get("ids");
            List<Object> args=new ArrayList<>(ids);
            String where=" WHERE c.inviter_id IN ("+MemberScopeQuery.placeholders(ids.size())+")"
                    +MemberScopeQuery.memberFilter("c.id",(MemberScope)parameters.get("scope"),args);
            // 只按当前页父节点分组，包含测试和停用会员，不读取孙辈或无权查看的子节点。
            return SqlParameters.bind("SELECT c.inviter_id,COUNT(*) direct_count FROM compute_member c"+where+" GROUP BY c.inviter_id",args,parameters);
        }
    }
}
