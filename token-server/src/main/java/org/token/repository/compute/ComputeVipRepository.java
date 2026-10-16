package org.token.repository.compute;

import org.token.exception.BusinessException;
import org.token.repository.dao.compute.ComputeVipMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 保留既有行投影格式，不缓存资格、余额或开关。 */
@Repository
public class ComputeVipRepository {
    private final ComputeVipMapper mapper;
    @Autowired public ComputeVipRepository(ComputeVipMapper mapper) { this.mapper=mapper; }
    public ComputeVipRepository(JdbcTemplate jdbc) { this(AnnotationMapperFactory.mapper(jdbc,ComputeVipMapper.class)); }
    public List<Map<String,Object>> availableBalance(long memberId,String currency) { return ComputeData.normalizeRows(mapper.availableBalance(memberId,currency)); }
    public List<Map<String,Object>> eligibilityLevels(long memberId) { return ComputeData.normalizeRows(mapper.eligibilityLevels(memberId)); }
    public List<Map<String,Object>> lockAutomaticLevels() { return ComputeData.normalizeRows(mapper.lockAutomaticLevels()); }
    public List<Map<String,Object>> directDeposits(long memberId) { return ComputeData.normalizeRows(mapper.directDeposits(memberId)); }
    public Map<String,Object> lockMember(long memberId) { return one(mapper.lockMember(memberId)); }
    public Map<String,Object> lockMemberId(long memberId) { return one(mapper.lockMemberId(memberId)); }
    public Map<String,Object> lockMemberLevel(long memberId) { return one(mapper.lockMemberLevel(memberId)); }
    public Map<String,Object> level(long levelId,boolean enabledOnly) { return one(enabledOnly?mapper.enabledLevel(levelId):mapper.level(levelId)); }
    public List<Map<String,Object>> lockSwitch(String key) { return ComputeData.normalizeRows(mapper.lockSwitch(key)); }
    public void initialize(long memberId,LocalDateTime start,LocalDateTime expiry) { mapper.initialize(memberId,start,expiry); }
    public void keepPeriod(long memberId,long levelId,LocalDateTime now) { mapper.keepPeriod(memberId,levelId,now); }
    public void resetPeriod(long memberId,long levelId,LocalDateTime start,LocalDateTime expiry) { mapper.resetPeriod(memberId,levelId,start,expiry); }
    public List<Map<String,Object>> names(List<Long> ids) { return ids.isEmpty()?List.of():ComputeData.normalizeRows(mapper.names(ids)); }
    private static Map<String,Object> one(List<Map<String,Object>> rows) {
        if(rows.isEmpty())throw new BusinessException("server.compute.notFound");
        return ComputeData.normalizeRow(rows.get(0));
    }
}
