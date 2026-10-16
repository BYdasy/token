package org.token.repository.compute;

import org.token.exception.BusinessException;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.repository.dao.compute.ComputeAccountMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Repository
public class ComputeAccountRepository {
    private final ComputeAccountMapper mapper;
    @Autowired public ComputeAccountRepository(ComputeAccountMapper mapper){this.mapper=mapper;}
    public ComputeAccountRepository(JdbcTemplate jdbc){this(AnnotationMapperFactory.mapper(jdbc,ComputeAccountMapper.class));}
    public Map<String,String> settings(){var result=new LinkedHashMap<String,String>();mapper.settings().forEach(row->result.put((String)row.get("setting_key"),(String)row.get("setting_value")));return result;}
    public List<Map<String,Object>> member(long id){
        var result=ComputeData.normalizeRows(mapper.member(id));
        result.forEach(row->{row.put("id",((Number)row.get("id")).longValue());row.put("level",((Number)row.get("level")).intValue());row.put("hasPayPassword",ComputeData.enabled(row.get("hasPayPassword")));});
        return result;
    }
    public void markTest(long id){mapper.markTest(id);}
    public Map<String,Object> creationInfo(long id){return ComputeData.normalizeRow(Objects.requireNonNull(mapper.creationInfo(id)));}
    public List<Long> memberInviter(String code){return mapper.memberInviter(code);}
    public long insertMember(Long id,Map<String,String> identity,String hash,int level,boolean enabled,String invite,Long inviterId,Long groupId,LocalDateTime now){
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",id);row.putAll(identity);
        row.put("passwordHash",hash);row.put("level",level);row.put("enabled",enabled);row.put("inviteCode",invite);
        row.put("inviterId",inviterId);row.put("groupId",groupId);row.put("now",now);
        mapper.insertMember(row);return id==null?((Number)Objects.requireNonNull(row.get("id"))).longValue():id;
    }
    public List<Map<String,Object>> login(String method,String account){return mapper.login(method,account);}
    public List<ComputeMemberPrincipal> authenticate(String digest,LocalDateTime cutoff){return mapper.authenticate(digest,cutoff).stream().map(row->new ComputeMemberPrincipal(((Number)row.get("id")).longValue(),(String)row.get("username"))).toList();}
    public List<String> lockActiveHashes(long id){return mapper.lockActiveHashes(id);}
    public String lockPassword(long id){return mapper.lockPassword(id);}
    public int deleteSessions(long id){return mapper.deleteSessions(id);}
    public void insertSession(String digest,long id,String credentialHash,LocalDateTime expires,LocalDateTime now){mapper.insertSession(digest,id,credentialHash,expires,now);}
    public void sessionActivity(String digest,LocalDateTime now,LocalDateTime expires,LocalDateTime cutoff){mapper.sessionActivity(digest,now,expires,cutoff);}
    public void recordAccess(long id,String ip,String country,boolean registration){mapper.recordAccess(id,ip,country);if(registration)mapper.recordRegistration(id,ip,country);}
    public void password(long id,String hash){mapper.password(id,hash);}
    public List<Map<String,Object>> lockPayCredentials(long id){return mapper.lockPayCredentials(id);}
    public void payPassword(long id,String hash){mapper.payPassword(id,hash);}
    public List<Long> lockMember(long id){return mapper.lockMember(id);}
    public void requireMemberLock(long id){if(mapper.lockMember(id).isEmpty())throw new org.springframework.dao.EmptyResultDataAccessException(1);}
    public void profile(long id,Map<String,Object> values){mapper.profile(id,values.get("username"),values.get("email"),values.get("phone"));}
    public void logout(String digest){mapper.logout(digest);}
    public List<Long> recoveryMember(String method,String account){return mapper.recoveryMember(method,account);}
    public int pendingRecoveryCount(long id){return mapper.pendingRecoveryCount(id);}
    public void requestRecovery(long id,String method,String account){mapper.requestRecovery(id,method,account);}
    public Map<String,Object> recoveries(int page,int size,String keyword,String startTime,String endTime,boolean global,boolean enabled,List<Long> roots){
        int limit=Math.max(1,Math.min(100,size));
        Map<String,Object> query=new LinkedHashMap<>();query.put("limit",limit);query.put("offset",(Math.max(1,Math.min(100000,page))-1)*limit);
        query.put("global",global);query.put("enabled",enabled);query.put("roots",roots);
        if(keyword!=null&&!keyword.isBlank()){
            if(keyword.length()>120)throw new BusinessException("server.compute.request.invalid");
            query.put("keyword","%"+keyword.trim()+"%");
        }
        try { query.put("startTime",filterTime(startTime));query.put("endTime",filterTime(endTime)); }
        catch(java.time.format.DateTimeParseException invalid){throw new BusinessException("server.compute.request.invalid");}
        long total=mapper.recoveryCount(query);
        return Map.of("records",ComputeData.normalizeRows(mapper.recoveries(query)),"total",total);
    }
    private static LocalDateTime filterTime(String value){return value==null||value.isBlank()?null:value.endsWith("Z")?LocalDateTime.ofInstant(Instant.parse(value),ZoneOffset.UTC):LocalDateTime.parse(value.replace(' ','T'));}
    public List<Long> pendingRecoveryMembers(long id){return mapper.pendingRecoveryMembers(id);}
    public List<Long> lockPendingRecovery(long id){return mapper.lockPendingRecovery(id);}
    public void expireRecoveries(long id){mapper.expireRecoveries(id);}
    public void issueRecovery(long id,String digest,LocalDateTime expires,int adminId){mapper.issueRecovery(id,digest,expires,adminId);}
    public List<Long> validRecoveryMembers(String digest){return mapper.validRecoveryMembers(digest);}
    public List<Long> lockValidRecovery(String digest){return mapper.lockValidRecovery(digest);}
    public void useRecovery(String digest){mapper.useRecovery(digest);}
    public static class Queries {
        private static String identity(Map<String,Object> values){
            String method=Objects.toString(values.get("method"),"");
            if(!Set.of("username","email","phone").contains(method))throw new IllegalArgumentException("会员身份查询字段不受支持");
            return method;
        }
        public static String login(Map<String,Object> values){return "SELECT id,password_hash,enabled FROM compute_member WHERE "+identity(values)+"=#{account} FOR UPDATE";}
        public static String recoveryMember(Map<String,Object> values){return "SELECT id FROM compute_member WHERE "+identity(values)+"=#{account} AND enabled=1 FOR UPDATE";}
        private static String recoverySource(Map<String,Object> values){
            String query=" FROM compute_recovery_request r LEFT JOIN compute_member m ON m.id=r.member_id LEFT JOIN ad_master a ON a.uid=r.reviewer_id WHERE 1=1";
            if(!Boolean.TRUE.equals(values.get("global")))query+=Boolean.TRUE.equals(values.get("enabled"))?MemberScopeQuery.boundDescendants("r.member_id",(List<?>)values.get("roots"),"roots"):" AND 1=0";
            if(values.get("keyword")!=null)query+=" AND (r.account LIKE #{keyword} OR m.username LIKE #{keyword})";
            if(values.get("startTime")!=null)query+=" AND r.created_at>=#{startTime}";
            if(values.get("endTime")!=null)query+=" AND r.created_at<=#{endTime}";
            return query;
        }
        public static String recoveryCount(Map<String,Object> values){return "SELECT COUNT(*)"+recoverySource(values);}
        public static String recoveries(Map<String,Object> values){return "SELECT r.id,r.member_id,r.method,r.account,r.status,r.created_at,m.username,a.username reviewer_username"+recoverySource(values)+" ORDER BY r.id DESC LIMIT #{limit} OFFSET #{offset}";}
    }
}
