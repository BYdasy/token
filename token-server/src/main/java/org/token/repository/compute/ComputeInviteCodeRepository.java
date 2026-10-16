package org.token.repository.compute;

import org.token.repository.dao.compute.ComputeInviteCodeMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ComputeInviteCodeRepository {
    private final ComputeInviteCodeMapper mapper;
    @Autowired public ComputeInviteCodeRepository(ComputeInviteCodeMapper mapper){this.mapper=mapper;}
    public ComputeInviteCodeRepository(JdbcTemplate jdbc){this(AnnotationMapperFactory.mapper(jdbc,ComputeInviteCodeMapper.class));}
    public Map<String,String> policy(){var result=new LinkedHashMap<String,String>();mapper.policy().forEach(row->result.put((String)row.get("setting_key"),(String)row.get("setting_value")));return result;}
    public boolean unused(String code){return mapper.historyCount(code)==0&&mapper.memberCount(code)==0;}
    public void recordCreated(long memberId,String code,LocalDateTime now){mapper.recordCreated(memberId,code,now);}
    public String lockCurrent(long memberId){return mapper.lockCurrent(memberId);}
    public List<Map<String,Object>> previous(long memberId,String requestNo){return mapper.previous(memberId,requestNo);}
    public List<Map<String,Object>> owner(String code){return mapper.owner(code);}
    public void recordRotation(long memberId,String current,String next,String requestNo,LocalDateTime now){mapper.recordRotation(memberId,current,next,requestNo,now);}
    public void retire(long memberId,String code,LocalDateTime now){mapper.retire(memberId,code,now);}
    public void replace(long memberId,String code,LocalDateTime now){mapper.replace(memberId,code,now);}
}
