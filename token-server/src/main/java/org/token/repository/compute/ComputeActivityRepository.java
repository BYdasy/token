package org.token.repository.compute;

import org.token.model.query.MemberScope;
import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeActivityMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeActivityService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeActivityRepository {
    private final ComputeActivityMapper mapper;

    @Autowired
    public ComputeActivityRepository(ComputeActivityMapper mapper) { this.mapper = mapper; }

    public ComputeActivityRepository(ComputeData data) {
        this(data.mapper(ComputeActivityMapper.class));
    }

    public List<Map<String, Object>> adminPageActivityRows(Object... args) {
        return ComputeData.normalizeRows(mapper.adminPageActivityRows(args));
    }

    public List<Map<String, Object>> adminPageActivityRows2(Object... args) {
        return ComputeData.normalizeRows(mapper.adminPageActivityRows2(args));
    }

    public Map<String, Object> detailActivityRow(Object... args) {
        return RepositoryResults.required(mapper.detailActivityRow(args));
    }

    public List<Map<String, Object>> decorateActivityPrizeRows(Object... args) {
        return ComputeData.normalizeRows(mapper.decorateActivityPrizeRows(args));
    }

    public Map<String, Object> decorateActivityRow(Object... args) {
        return RepositoryResults.required(mapper.decorateActivityRow(args));
    }

    public int saveSettingWrite(Object... args) {
        return mapper.saveSettingWrite(args);
    }

    public String saveSettingValue(Object... args) {
        return RepositoryResults.scalar(mapper.saveSettingValue(args), String.class);
    }

    public Map<String, Object> saveActivityRow(Object... args) {
        return RepositoryResults.required(mapper.saveActivityRow(args));
    }

    public long saveCurrencyCount(Object... args) {
        return RepositoryResults.scalar(mapper.saveCurrencyCount(args), Long.class);
    }

    public int saveSettingWrite2(Object... args) {
        return mapper.saveSettingWrite2(args);
    }

    public long saveWheelMemberCount(Object... args) {
        return RepositoryResults.scalar(mapper.saveWheelMemberCount(args), Long.class);
    }

    public int saveActivityPrizeWrite(Object... args) {
        return mapper.saveActivityPrizeWrite(args);
    }

    public List<Map<String, Object>> saveActivityPrizeRows(Object... args) {
        return ComputeData.normalizeRows(mapper.saveActivityPrizeRows(args));
    }

    public Map<String, Object> saveActivityRow2(Object... args) {
        return RepositoryResults.required(mapper.saveActivityRow2(args));
    }

    public int deleteActivityWrite(Object... args) {
        return mapper.deleteActivityWrite(args);
    }

    public Map<String, Object> participateMemberRow(Object... args) {
        return RepositoryResults.required(mapper.participateMemberRow(args));
    }

    public List<Map<String, Object>> participateActivityParticipationRows(Object... args) {
        return ComputeData.normalizeRows(mapper.participateActivityParticipationRows(args));
    }

    public List<Map<String, Object>> participateActivityPrizeRows(Object... args) {
        return ComputeData.normalizeRows(mapper.participateActivityPrizeRows(args));
    }

    public int participateActivityPrizeWrite(Object... args) {
        return mapper.participateActivityPrizeWrite(args);
    }

    public int participateActivityWrite(Object... args) {
        return mapper.participateActivityWrite(args);
    }

    public int participateActivityWrite2(Object... args) {
        return mapper.participateActivityWrite2(args);
    }

    public Map<String, Object> participateActivityParticipationRow(Object... args) {
        return RepositoryResults.required(mapper.participateActivityParticipationRow(args));
    }

    public BigDecimal checkEligibilityDepositValue(Object... args) {
        return RepositoryResults.scalar(mapper.checkEligibilityDepositValue(args), BigDecimal.class);
    }

    public BigDecimal checkEligibilityOrderValue(Object... args) {
        return RepositoryResults.scalar(mapper.checkEligibilityOrderValue(args), BigDecimal.class);
    }

    public long todayCountActivityParticipationCount(Object... args) {
        return RepositoryResults.scalar(mapper.todayCountActivityParticipationCount(args), Long.class);
    }

    public long participationCountActivityParticipationCount(Object... args) {
        return RepositoryResults.scalar(mapper.participationCountActivityParticipationCount(args), Long.class);
    }

    public long nextRedemptionCodeActivityParticipationCount(Object... args) {
        return RepositoryResults.scalar(mapper.nextRedemptionCodeActivityParticipationCount(args), Long.class);
    }
    public Map<String,Object> activityPage(Map<String,String> query,boolean publicOnly,long primaryWheel){
        ComputeActivityQueries.validate(query,true,publicOnly);
        var p=ComputeData.fields("query",query,"publicOnly",publicOnly,"primaryWheel",primaryWheel);long total=mapper.pageCount(p);
        return ComputeData.fields("records",ComputeData.normalizeRows(mapper.pageRows(p)),"total",total);
    }
    public long participationCount(long id,long memberId,long round,LocalDateTime start,LocalDateTime end){return mapper.participationCount(ComputeData.fields("id",id,"memberId",memberId,"round",round,"start",start,"end",end));}
    public Map<String,Object> participations(Long memberId,Map<String,String> query,MemberScope scope){
        ComputeActivityQueries.validate(query,false,false);
        var p=ComputeData.fields("memberId",memberId,"query",query,"scope",scope);long total=mapper.participationsCount(p);
        return ComputeData.fields("records",ComputeData.normalizeRows(mapper.participationsRows(p)),"total",total);
    }
}
