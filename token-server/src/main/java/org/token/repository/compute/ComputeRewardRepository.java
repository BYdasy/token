package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeRewardMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeRewardService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeRewardRepository {
    private final ComputeRewardMapper mapper;

    @Autowired
    public ComputeRewardRepository(ComputeRewardMapper mapper) { this.mapper = mapper; }

    public ComputeRewardRepository(ComputeData data) {
        this(data.mapper(ComputeRewardMapper.class));
    }

    public List<Map<String, Object>> settingsCurrencyAssetRows(Object... args) {
        return ComputeData.normalizeRows(mapper.settingsCurrencyAssetRows(args));
    }

    public Integer saveCurrencyAssetCount(Object... args) {
        return RepositoryResults.scalar(mapper.saveCurrencyAssetCount(args), Integer.class);
    }

    public int saveRewardConfigurationWrite(Object... args) {
        return mapper.saveRewardConfigurationWrite(args);
    }

    public Map<String, Object> onDepositApprovedDepositRow(Object... args) {
        return RepositoryResults.required(mapper.onDepositApprovedDepositRow(args));
    }

    public Integer onDepositApprovedLedgerCount(Object... args) {
        return RepositoryResults.scalar(mapper.onDepositApprovedLedgerCount(args), Integer.class);
    }

    public List<Map<String, Object>> onIncomePostedLedgerRows(Object... args) {
        return ComputeData.normalizeRows(mapper.onIncomePostedLedgerRows(args));
    }

    public Integer effectiveRewardsRewardAwardCount(Object... args) {
        return RepositoryResults.scalar(mapper.effectiveRewardsRewardAwardCount(args), Integer.class);
    }

    public List<Map<String, Object>> vipRewardsMemberRows(Object... args) {
        return ComputeData.normalizeRows(mapper.vipRewardsMemberRows(args));
    }

    public Map<String, Object> awardMemberRow(Object... args) {
        return RepositoryResults.required(mapper.awardMemberRow(args));
    }

    public Integer awardRewardAwardCount(Object... args) {
        return RepositoryResults.scalar(mapper.awardRewardAwardCount(args), Integer.class);
    }

    public int awardWalletWrite(Object... args) {
        return mapper.awardWalletWrite(args);
    }

    public Map<String, Object> awardWalletRow(Object... args) {
        return RepositoryResults.required(mapper.awardWalletRow(args));
    }

    public int awardWalletWrite2(Object... args) {
        return mapper.awardWalletWrite2(args);
    }

    public Map<String, Object> centerMemberRow(Object... args) {
        return RepositoryResults.required(mapper.centerMemberRow(args));
    }

    public List<Map<String, Object>> centerLevelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.centerLevelRows(args));
    }

    public List<Map<String, Object>> centerRewardAwardRows(Object... args) {
        return ComputeData.normalizeRows(mapper.centerRewardAwardRows(args));
    }

    public Long recordsRewardAwardCount(Object... args) {
        return RepositoryResults.scalar(mapper.recordsRewardAwardCount(args), Long.class);
    }

    public BigDecimal totalRewardAwardValue(Object... args) {
        return RepositoryResults.scalar(mapper.totalRewardAwardValue(args), BigDecimal.class);
    }

    public List<Map<String, Object>> firstDepositDepositRows(Object... args) {
        return ComputeData.normalizeRows(mapper.firstDepositDepositRows(args));
    }

    public Integer processedRewardEventCount(Object... args) {
        return RepositoryResults.scalar(mapper.processedRewardEventCount(args), Integer.class);
    }

    public int markRewardEventWrite(Object... args) {
        return mapper.markRewardEventWrite(args);
    }

    public Map<String, Object> configurationRewardConfigurationRow(Object... args) {
        return org.token.repository.support.DomainSettingDocument.unpack(
                RepositoryResults.required(mapper.configurationRewardConfigurationRow(args)),
                "configurationJson","activatedAt","activatedAt");
    }
    public long qualifiedCount(long owner,String currency,Long customer,boolean first,BigDecimal threshold){return mapper.qualifiedCount(owner,currency,customer,first,threshold);}
    public List<Map<String,Object>> recordsPage(long memberId,int limit,long offset){return ComputeData.normalizeRows(mapper.recordsPage(memberId,limit,offset));}
}
