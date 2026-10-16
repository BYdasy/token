package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeCurrencyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeCurrencyService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeCurrencyRepository {
    private final ComputeCurrencyMapper mapper;

    @Autowired
    public ComputeCurrencyRepository(ComputeCurrencyMapper mapper) { this.mapper = mapper; }

    public ComputeCurrencyRepository(ComputeData data) {
        this(data.mapper(ComputeCurrencyMapper.class));
    }

    public Integer assertAddressCapacityAddressCount(Object... args) {
        return RepositoryResults.scalar(mapper.assertAddressCapacityAddressCount(args), Integer.class);
    }

    public Integer assertAddressCapacityMemberValue(Object... args) {
        return RepositoryResults.scalar(mapper.assertAddressCapacityMemberValue(args), Integer.class);
    }

    public Long assertAddressCapacityAddressCount2(Object... args) {
        return RepositoryResults.scalar(mapper.assertAddressCapacityAddressCount2(args), Long.class);
    }

    public List<Map<String, Object>> manualWithdrawalRecipientAddressRows(Object... args) {
        return ComputeData.normalizeRows(mapper.manualWithdrawalRecipientAddressRows(args));
    }

    public List<Map<String, Object>> optionsCurrencyAssetRows(Object... args) {
        return ComputeData.normalizeRows(mapper.optionsCurrencyAssetRows(args));
    }

    public List<Map<String, Object>> optionsCurrencyNetworkRows(Object... args) {
        return ComputeData.normalizeRows(mapper.optionsCurrencyNetworkRows(args));
    }

    public List<Map<String, Object>> saveSettingRows(Object... args) {
        return RepositoryResults.rawRows(mapper.saveSettingRows(args));
    }

    public Map<String, Object> channelValuesCurrencyAssetRow(Object... args) {
        return RepositoryResults.required(mapper.channelValuesCurrencyAssetRow(args));
    }

    public Map<String, Object> channelValuesCurrencyNetworkRow(Object... args) {
        return RepositoryResults.required(mapper.channelValuesCurrencyNetworkRow(args));
    }

    public List<Map<String, Object>> syncedChannelMcagChannelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.syncedChannelMcagChannelRows(args));
    }

    public List<Map<String, Object>> syncedNetworkCodesMcagChannelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.syncedNetworkCodesMcagChannelRows(args));
    }

    public List<Map<String, Object>> publicChannels() {
        return ComputeData.normalizeRows(mapper.publicChannels());
    }

    public List<Map<String, Object>> manualDepositChannels(String currency) {
        return ComputeData.normalizeRows(mapper.manualDepositChannels(currency));
    }

    public List<Map<String, Object>> channelOptions() {
        return ComputeData.normalizeRows(mapper.channelOptions());
    }

    public List<Map<String,Object>> visibleChannelOptions(String merchant,String environment) {
        return ComputeData.normalizeRows(mapper.visibleChannelOptions(merchant,environment));
    }

    public List<Map<String, Object>> channelById(long id) {
        return ComputeData.normalizeRows(mapper.channelById(id));
    }

    public List<Map<String, Object>> channelByAssetNetwork(String currency, String network) {
        return ComputeData.normalizeRows(mapper.channelByAssetNetwork(currency, network));
    }

    public Integer activeCurrencyCount(String currency) {
        return mapper.activeCurrencyCount(currency);
    }

    public Integer metadataUseCount(boolean asset, String code) {
        return mapper.metadataUseCount(asset, code);
    }

    public Map<String,Object> page(String kind, Map<String,String> query) {
        return page(kind,query,"","");
    }

    public Map<String,Object> page(String kind,Map<String,String> query,String merchant,String environment) {
        long total = RepositoryResults.scalar(mapper.page(kind, query, true,merchant,environment), Long.class);
        return ComputeData.fields("records", ComputeData.normalizeRows(mapper.page(kind, query, false,merchant,environment)), "total", total);
    }
}
