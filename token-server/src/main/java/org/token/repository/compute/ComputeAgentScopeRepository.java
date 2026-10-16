package org.token.repository.compute;

import org.token.repository.dao.compute.ComputeAgentScopeMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class ComputeAgentScopeRepository {
    private final ComputeAgentScopeMapper mapper;
    @Autowired public ComputeAgentScopeRepository(ComputeAgentScopeMapper mapper){this.mapper=mapper;}
    public ComputeAgentScopeRepository(JdbcTemplate jdbc){this(AnnotationMapperFactory.mapper(jdbc,ComputeAgentScopeMapper.class));}
    public List<Long> roots(int uid){return mapper.roots(uid);}
    public List<Long> activeRoots(List<Long> roots){return roots.isEmpty()?List.of():mapper.activeRoots(roots);}
    public boolean configured(int uid){return mapper.configured(uid)>0;}
    public boolean contains(long id,boolean global,boolean enabled,List<Long> roots){return mapper.memberCount(id,global,enabled,roots)==1;}
    public boolean activeMember(long id){return mapper.activeMember(id)==1;}
    public List<String> invitationEnabled(){return mapper.invitationEnabled();}
    public List<Long> inviter(String code){return mapper.inviter(code);}
    public static class Queries {
        public static String memberCount(Map<String,Object> values){
            String query="SELECT COUNT(*) FROM compute_member m WHERE m.id=#{id}";
            if(Boolean.TRUE.equals(values.get("global")))return query;
            if(!Boolean.TRUE.equals(values.get("enabled")))return query+" AND 1=0";
            return query+MemberScopeQuery.boundDescendants("m.id",(List<?>)values.get("roots"),"roots");
        }
    }
}
