package org.token.repository.compute;

import org.token.model.query.MemberScope;
import org.token.model.query.ActivityDepositQuery;
import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeWheelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeWheelService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeWheelRepository {
    private final ComputeWheelMapper mapper;

    @Autowired
    public ComputeWheelRepository(ComputeWheelMapper mapper) { this.mapper = mapper; }

    public ComputeWheelRepository(ComputeData data) {
        this(data.mapper(ComputeWheelMapper.class));
    }

    public long normalizeMemberGroupCount(Object... args) {
        return RepositoryResults.scalar(mapper.normalizeMemberGroupCount(args), Long.class);
    }

    public List<Map<String, Object>> assertChestTargetsUnchangedWheelMemberRows(Object... args) {
        return ComputeData.normalizeRows(mapper.assertChestTargetsUnchangedWheelMemberRows(args));
    }

    public int syncWheelMemberWrite(Object... args) {
        return mapper.syncWheelMemberWrite(args);
    }

    public Map<String, Object> syncWheelMemberRow(Object... args) {
        return RepositoryResults.required(mapper.syncWheelMemberRow(args));
    }

    public int syncWheelMemberWrite2(Object... args) {
        return mapper.syncWheelMemberWrite2(args);
    }

    public int syncWheelMemberWrite3(Object... args) {
        return mapper.syncWheelMemberWrite3(args);
    }

    public Map<String, Object> syncWheelMemberRow2(Object... args) {
        return RepositoryResults.required(mapper.syncWheelMemberRow2(args));
    }

    public Map<String, Object> memberStatusMemberRow(Object... args) {
        return RepositoryResults.required(mapper.memberStatusMemberRow(args));
    }

    public Map<String, Object> memberStatusActivityRow(Object... args) {
        return RepositoryResults.required(mapper.memberStatusActivityRow(args));
    }

    public Map<String, Object> requireAttemptsMemberRow(Object... args) {
        return RepositoryResults.required(mapper.requireAttemptsMemberRow(args));
    }

    public int consumeWheelMemberWrite(Object... args) {
        return mapper.consumeWheelMemberWrite(args);
    }

    public List<Map<String, Object>> adjustWheelAdjustmentRows(Object... args) {
        return ComputeData.normalizeRows(mapper.adjustWheelAdjustmentRows(args));
    }

    public long adjustActivityPrizeCount(Object... args) {
        return RepositoryResults.scalar(mapper.adjustActivityPrizeCount(args), Long.class);
    }

    public int adjustWheelMemberWrite(Object... args) {
        return mapper.adjustWheelMemberWrite(args);
    }

    public Map<String, Object> adjustWheelAdjustmentRow(Object... args) {
        return RepositoryResults.required(mapper.adjustWheelAdjustmentRow(args));
    }

    public Map<String, Object> redeemActivityParticipationRow(Object... args) {
        return RepositoryResults.required(mapper.redeemActivityParticipationRow(args));
    }

    public Map<String, Object> redeemMemberRow(Object... args) {
        return RepositoryResults.required(mapper.redeemMemberRow(args));
    }

    public Map<String, Object> redeemActivityRow(Object... args) {
        return RepositoryResults.required(mapper.redeemActivityRow(args));
    }

    public Map<String, Object> redeemActivityParticipationRow2(Object... args) {
        return RepositoryResults.required(mapper.redeemActivityParticipationRow2(args));
    }

    public int redeemActivityParticipationWrite(Object... args) {
        return mapper.redeemActivityParticipationWrite(args);
    }

    public int resetActivityWrite(Object... args) {
        return mapper.resetActivityWrite(args);
    }

    public List<Map<String, Object>> membersActivityPrizeRows(Object... args) {
        return ComputeData.normalizeRows(mapper.membersActivityPrizeRows(args));
    }

    public long syncTierGrantsActivityAttemptGrantCount(Object... args) {
        return RepositoryResults.scalar(mapper.syncTierGrantsActivityAttemptGrantCount(args), Long.class);
    }

    public long grantCountActivityAttemptGrantValue(Object... args) {
        return RepositoryResults.scalar(mapper.grantCountActivityAttemptGrantValue(args), Long.class);
    }

    public List<Map<String, Object>> tierTotalActivityAttemptGrantRows(Object... args) {
        return ComputeData.normalizeRows(mapper.tierTotalActivityAttemptGrantRows(args));
    }

    public long tierTotalWheelMemberValue(Object... args) {
        return RepositoryResults.scalar(mapper.tierTotalWheelMemberValue(args), Long.class);
    }

    public long recordedChestGrantsActivityAttemptGrantValue(Object... args) {
        return RepositoryResults.scalar(mapper.recordedChestGrantsActivityAttemptGrantValue(args), Long.class);
    }

    public List<Map<String, Object>> chestTierTotalActivityAttemptGrantRows(Object... args) {
        return ComputeData.normalizeRows(mapper.chestTierTotalActivityAttemptGrantRows(args));
    }

    public List<Map<String, Object>> chestTierTotalWheelMemberRows(Object... args) {
        return ComputeData.normalizeRows(mapper.chestTierTotalWheelMemberRows(args));
    }
    public Map<String,Object> members(long activityId,long round,Map<String,String> query,MemberScope scope){
        var p=ComputeData.fields("activityId",activityId,"round",round,"query",query,"scope",scope);long total=mapper.membersCount(p);
        return ComputeData.fields("records",ComputeData.normalizeRows(mapper.membersRows(p)),"total",total);
    }
    public Map<String,Object> statistics(Map<String,String> query,MemberScope scope){
        var p=ComputeData.fields("query",query,"scope",scope);
        return Map.of("currencies",ComputeData.normalizeRows(mapper.statisticsCurrencies(p)),"prizes",ComputeData.normalizeRows(mapper.statisticsPrizes(p)));
    }
    public BigDecimal earned(ActivityDepositQuery query,BigDecimal threshold,boolean first){return mapper.earned(ComputeData.fields("query",query,"threshold",threshold,"first",first));}
    public List<Map<String,Object>> wheelTiers(ActivityDepositQuery query,boolean first){return ComputeData.normalizeRows(mapper.wheelTiers(ComputeData.fields("query",query,"first",first)));}
    public List<Map<String,Object>> chestTiers(ActivityDepositQuery query,boolean single,boolean first){return ComputeData.normalizeRows(mapper.chestTiers(ComputeData.fields("query",query,"single",single,"first",first)));}
    public List<Map<String,Object>> priorGrants(long activityId,long round,long memberId,boolean chest){return ComputeData.normalizeRows(mapper.priorGrants(ComputeData.fields("activityId",activityId,"round",round,"memberId",memberId,"chest",chest)));}
}
