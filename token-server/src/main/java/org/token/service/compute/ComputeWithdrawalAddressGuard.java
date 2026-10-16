package org.token.service.compute;

import org.token.repository.compute.ComputeWithdrawalAddressGuardRepository;

import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 地址归属保护：EVM跨链同一所有者；平台充值地址始终不可作为提款目的地。 */
@Service
public class ComputeWithdrawalAddressGuard {
    private final ComputeData data;
    private final ComputeWithdrawalAddressGuardRepository repository;
    private final ComputeMcagProperties config;
    private final ObjectProvider<ComputeMcagClient> clients;
    private final ComputeMcagChannelService channels;

    public ComputeWithdrawalAddressGuard(ComputeData data, ComputeMcagProperties config,
            ObjectProvider<ComputeMcagClient> clients, ComputeMcagChannelService channels) {
        this.data=data; this.config=config; this.clients=clients; this.channels=channels;

        this.repository = new ComputeWithdrawalAddressGuardRepository(data);
    }

    /** 只供服务器内本次请求使用，不能由接口请求构造或跨请求缓存。 */
    public static final class Proof {
        private final long memberId;
        private final State snapshot;
        private Proof(long memberId, State snapshot) { this.memberId=memberId; this.snapshot=snapshot; }
    }

    private record State(String fingerprint,Map<String,Object> payoutSnapshot) {}
    record Check(long memberId, Map<String,Object> channel, ComputeCurrencyService.AddressData recipient) {}
    record Preparation(Map<String,Object> replay, Check check) {}

    /** 禁止挂起已有事务后外呼：挂起不会释放调用方已经持有的数据库锁。 */
    static void assertOutsideTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Crypto 地址归属检查必须在资金和地址事务开始前执行");
    }

    Proof verifyRemote(Check check) {
        assertOutsideTransaction();
        State snapshot=snapshot(check.channel(),check.recipient());
        if(remoteRequired(check.channel())) {
            var mapped=channels.requireChannel(text(check.channel().get("currency")),text(check.channel().get("network")));
            var client=clients==null?null:clients.getIfAvailable();
            if(client==null)throw new BusinessException(503,"server.compute.gateway.notConfigured");
            // 只读查询已登记的永久归属，不生成地址、不实时查询链节点。
            var result=client.request("customer-addresses/query",Map.of("network_id",mapped.getNetworkId(),"address",check.recipient().address()));
            if(!(result.get("registered") instanceof Boolean registered))throw new BusinessException(502,"server.compute.payment.invalidResponse");
            if(registered)throw new BusinessException("server.compute.v15.platformDepositAddress");
        }
        if(!snapshot.fingerprint().equals(snapshot(check.channel(),check.recipient()).fingerprint()))throw new BusinessException("server.compute.recipientChanged");
        return new Proof(check.memberId(),snapshot);
    }

    private boolean remoteRequired(Map<String,Object> channel) {
        return config.isEnabled() && "CRYPTO".equals(channel.get("assetType"));
    }

    private State snapshot(Map<String,Object> channel, ComputeCurrencyService.AddressData recipient) {
        var value=new TreeMap<String,Object>();
        for(String field:List.of("currency","network","assetType","chainType","networkType","requiredFields","amountScale",
                "assetAmountScale","enabled","assetEnabled","networkEnabled","withdrawEnabled","withdrawMin","withdrawMax",
                "withdrawFeeRate","withdrawFeeFixed","supportAccountTypes"))value.put(field,channel.get(field));
        value.put("recipient",fields("address",recipient.address(),"memo",recipient.memo(),"fields",new TreeMap<>(recipient.fields())));
        var identity=fields("enabled",config.isEnabled(),"baseUrl",config.getBaseUrl(),"merchantId",config.getMerchantId(),
                "environment",config.getEnvironment(),"credentialId",config.getCredentialId());
        value.put("gateway",identity);
        Map<String,Object> payoutSnapshot=fields("mode","MANUAL");
        if(enabled(identity.get("enabled")) && "CRYPTO".equals(channel.get("assetType"))) {
            var mapped=channels.requireChannel(text(channel.get("currency")),text(channel.get("network")));
            if(!mapped.isActive())throw new BusinessException("server.compute.unsupportedCurrency");
            value.put("mapping",fields("assetId",mapped.getAssetId(),"networkId",mapped.getNetworkId(),"version",mapped.getMappingVersion(),
                    "active",mapped.isActive(),"payoutEnabled",mapped.isPayoutEnabled(),"decimals",mapped.getDecimals(),
                    "minimumPayout",mapped.getMinimumPayout()));
            payoutSnapshot=fields("mode","MCAG","merchantId",identity.get("merchantId"),"environment",identity.get("environment"),
                    "assetId",mapped.getAssetId(),"networkId",mapped.getNetworkId(),"mappingVersion",mapped.getMappingVersion());
        }
        return new State(data.json(value),Collections.unmodifiableMap(payoutSnapshot));
    }

    /** 仅执行本地保护；最终保存/冻结必须与本方法处于同一事务。 */
    public String assertAllowed(long memberId, Map<String,Object> channel, ComputeCurrencyService.AddressData recipient) {
        return assertAllowed(memberId,channel,recipient,null);
    }

    public String assertAllowed(long memberId, Map<String,Object> channel, ComputeCurrencyService.AddressData recipient, Proof proof) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("提款地址保护必须与本地保存或冻结处于同一事务");

        String chain=text(channel.get("chainType")), network=text(channel.get("network"));
        String normalized=normalize(chain,recipient.address());
        String key=key(chain,network,recipient.address(),recipient.memo());
        repository.assertAllowedAddressGuardWrite(key, now());
        repository.assertAllowedAddressGuardRows(key);
        assertProof(memberId,channel,recipient,proof);
        boolean unique=enabled(data.setting("withdrawalAddressUnique","true"));
        var candidates=repository.assertAllowedAddressRows(memberId, key, recipient.address().toLowerCase(Locale.ROOT));
        for(var row:candidates) {
            if(!key.equals(key(text(row.get("chainType")),text(row.get("network")),text(row.get("address")),text(row.get("memo")))))continue;
            if(enabled(row.get("enabled")) && !enabled(row.get("removed"))) {
                if(unique)throw new BusinessException("server.compute.v15.addressAlreadyBound");
            } else {
                // 无效历史绑定不拦重复地址；仍在途的原会员提款必须保留归属，跨EVM网络同样核对。
                assertRemovable(row);
            }
        }
        if (!"CRYPTO".equals(channel.get("assetType"))) return key;
        // 旧地址即使不再作为当前充值入口，也不能绑定为提款地址；不受跨用户开关影响。
        var bindings=repository.assertAllowedMcagAddressRows(recipient.address().toLowerCase(Locale.ROOT));
        for(var row:bindings) if(sameNetwork(chain,network,text(row.get("chainType")),text(row.get("network")))
                && normalized.equals(normalize(text(row.get("chainType")),text(row.get("address")))))
            throw new BusinessException("server.compute.v15.platformDepositAddress");
        return key;
    }

    private void assertProof(long memberId,Map<String,Object> channel,ComputeCurrencyService.AddressData recipient,Proof proof) {
        if(proof!=null) {
            if(proof.memberId!=memberId || !proof.snapshot.fingerprint().equals(snapshot(channel,recipient).fingerprint()))
                throw new BusinessException("server.compute.recipientChanged");
        } else if(remoteRequired(channel)) {
            throw new BusinessException(503,"server.compute.gateway.notConfigured");
        }
    }

    /** 落单前再次复核，保存远端检查绑定的身份；不另查新映射替换已经核验的网络。 */
    Map<String,Object> payoutSnapshot(long memberId,Map<String,Object> channel,ComputeCurrencyService.AddressData recipient,Proof proof) {
        assertProof(memberId,channel,recipient,proof);
        return proof==null?fields("mode","MANUAL"):new LinkedHashMap<>(proof.snapshot.payoutSnapshot());
    }

    public void assertRemovable(Map<String,Object> address) {
        // 使用当前读，不能沿用取得地址保护锁前的RR快照而漏掉刚提交的提款。
        var pending=repository.assertRemovableWithdrawalRows(address.get("memberId"));
        var chain=repository.assertRemovableCurrencyNetworkRow(address.get("network"));
        String family=text(chain.get("chainType"));
        for(var row:pending) if(sameNetwork(family,text(address.get("network")),text(row.get("chainType")),text(row.get("network")))
                && normalize(family,text(row.get("address"))).equals(normalize(family,text(address.get("address")))))
            throw new BusinessException("server.compute.v15.addressInUse");
    }

    public static String key(String chain,String network,String address,String memo) {
        String scope="EVM".equals(chain)?"EVM":network;
        String effectiveMemo="EVM".equals(chain)?"":memo;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((scope+"|"+normalize(chain,address)+"|"+effectiveMemo).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("缺少SHA-256算法",impossible);}
    }

    public static String normalize(String chain,String address) {
        if("EVM".equals(chain)||Set.of("BTC","BITCOIN").contains(chain)&&address.toLowerCase(Locale.ROOT).startsWith("bc1")) return address.toLowerCase(Locale.ROOT);
        return address;
    }
    private static boolean sameNetwork(String chain,String network,String otherChain,String otherNetwork) {
        return "EVM".equals(chain)&&"EVM".equals(otherChain)||network.equals(otherNetwork);
    }
}
