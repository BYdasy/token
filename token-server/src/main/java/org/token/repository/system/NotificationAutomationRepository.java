package org.token.repository.system;

import org.token.repository.dao.NotificationAutomationMapper;
import org.token.repository.compute.ComputeData;
import org.token.model.query.MemberScope;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.*;

@Repository
public class NotificationAutomationRepository {
    private final NotificationAutomationMapper mapper;
    public NotificationAutomationRepository(NotificationAutomationMapper mapper){this.mapper=mapper;}
    public Map<String,Object> configuration(boolean lock){return org.token.repository.support.DomainSettingDocument.unpack(lock?mapper.lockConfiguration():mapper.configuration(),"configJson","collectionStartedAt","createdAt");}
    public int save(String json,int uid,LocalDateTime now,long version){return mapper.saveConfiguration(json,uid,now,version);}
    public int collectEvents(boolean withdrawal,LocalDateTime since,LocalDateTime now){return mapper.collectFinanceEvents(withdrawal,since,now);}
    public long maximum(boolean withdrawal,MemberScope scope){return ((Number)ComputeData.normalizeRows(mapper.financeEvents(withdrawal,scope,null,true,1,null)).get(0).get("cursor")).longValue();}
    public List<Map<String,Object>> events(boolean withdrawal,MemberScope scope,long after,int limit,LocalDateTime since){return ComputeData.normalizeRows(mapper.financeEvents(withdrawal,scope,after,false,limit,since));}
    public List<Map<String,Object>> candidates(String event,LocalDateTime since,LocalDateTime before,java.math.BigDecimal threshold){return ComputeData.normalizeRows(mapper.candidates(event,since,before,threshold));}
    public boolean eligible(String event,long source,LocalDateTime since,LocalDateTime before,java.math.BigDecimal threshold){return mapper.eligible(event,source,since,before,threshold)>0;}
    public List<Map<String,Object>> administrators(){return ComputeData.normalizeRows(mapper.administrators());}
    public Map<String,Object> administrator(int uid){return ComputeData.normalizeRow(mapper.administrator(uid));}
    public boolean delivered(String event,String source,String target){return mapper.delivered(event,source,target)>0;}
    public void claim(String event,String source,String target,LocalDateTime now){mapper.claim(event,source,target,now);}
    public void complete(String event,String source,String target,String status,LocalDateTime now){mapper.complete(event,source,target,status,now);}
    public String energyState(){var rows=mapper.energyState();return rows.isEmpty()?null:rows.get(0);}
    public void energyState(String source,LocalDateTime now){mapper.energyStateSet(source,now);}
}
