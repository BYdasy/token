package org.token.service.compute;

import org.token.repository.compute.ComputeMcagChannelRepository;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.springframework.stereotype.Service;

import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 商户通道只从已同步的数据库映射读取；停用映射仍保留身份，不能降级为人工支付。 */
@Service
public class ComputeMcagChannelService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeMcagChannelService(ComputeData data, ComputeMcagProperties config, ComputeMcagChannelRepository repository) {
        this.data = data;
        this.config = config;
        this.repository = repository;
    }

    public ComputeMcagChannelService(ComputeData data, ComputeMcagProperties config) {
        this(data, config, new ComputeMcagChannelRepository(data));
    }

    private final ComputeData data;
    private final ComputeMcagChannelRepository repository;
    private final ComputeMcagProperties config;

    public ComputeMcagProperties.Channel findChannel(String currency, String network) {
        if (!config.isEnabled()) return null;
        return first(repository.findChannelMcagChannelRows(config.getMerchantId(), config.getEnvironment(), currency, network));
    }

    public ComputeMcagProperties.Channel findByAsset(String assetId, String networkId) {
        if (!config.isEnabled()) return null;
        return first(repository.findByAssetMcagChannelRows(config.getMerchantId(), config.getEnvironment(), assetId, networkId));
    }

    public ComputeMcagProperties.Channel requireChannel(String currency, String network) {
        var result = findChannel(currency, network);
        if (result == null) throw new BusinessException(503, "server.compute.v15.channelNotSynced");
        return result;
    }

    public List<ComputeMcagProperties.Channel> listChannels() {
        if (!config.isEnabled()) return List.of();
        return listSyncedChannels();
    }

    /** 管理配置保留已同步身份；此查询结果不表示支付接入已启用。 */
    public List<ComputeMcagProperties.Channel> listSyncedChannels() {
        if (text(config.getMerchantId()).isEmpty()) return List.of();
        return repository.listSyncedChannelsMcagChannelRows(config.getMerchantId(), config.getEnvironment()).stream().map(this::channel).toList();
    }

    public Map<String,Object> snapshot(ComputeMcagProperties.Channel channel) {
        return fields("mode", "MCAG", "merchantId", config.getMerchantId(), "environment", config.getEnvironment(),
                "assetId", channel.getAssetId(), "networkId", channel.getNetworkId(), "mappingVersion", channel.getMappingVersion());
    }

    private ComputeMcagProperties.Channel first(List<Map<String,Object>> rows) {
        if (rows.size() > 1) throw new BusinessException("server.compute.mcag.channelMismatch");
        return rows.isEmpty() ? null : channel(rows.get(0));
    }

    private ComputeMcagProperties.Channel channel(Map<String,Object> row) {
        var result = new ComputeMcagProperties.Channel();
        result.setCurrency(text(row.get("currency"))); result.setNetwork(text(row.get("network")));
        result.setAssetId(text(row.get("assetId"))); result.setNetworkId(text(row.get("networkId")));
        result.setMappingVersion(number(row.get("mappingVersion"))); result.setDecimals((int)number(row.get("decimals")));
        result.setActive(enabled(row.get("active")));
        result.setDepositEnabled(enabled(row.get("active")) && enabled(row.get("depositEnabled")));
        result.setPayoutEnabled(enabled(row.get("active")) && enabled(row.get("payoutEnabled")));
        var metadata=data.jsonMap(row.get("metadataJson"));
        result.setMinimumDeposit(decimal(metadata.get("minimum_deposit")));
        result.setMinimumPayout(decimal(metadata.get("minimum_payout")));
        return result;
    }
}
