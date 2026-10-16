package org.token.service.compute;

import org.token.repository.compute.ComputeMcagAddressRepository;

import org.token.repository.compute.ComputeMcagDepositRepository;

import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 管理收款地址永久归属、展示指针与租约，以及 MCAG 事件收件和共享游标。 */
@Service
@ConditionalOnProperty(name = "token.mcag.enabled", havingValue = "true")
public class ComputeMcagDepositService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeMcagDepositService(ComputeData data,ComputeMcagProperties config,
            ComputeMcagDepositRepository repository,ComputeMcagAddressRepository addresses) {
        this.data = data;
        this.config = config;
        this.repository = repository;
        this.addresses = addresses;
    }

    public ComputeMcagDepositService(ComputeData data,ComputeMcagProperties config,ComputeMcagDepositRepository repository) {
        this(data,config,repository,new ComputeMcagAddressRepository(data));
    }

    public ComputeMcagDepositService(ComputeData data, ComputeMcagProperties config) {
        this(data, config, new ComputeMcagDepositRepository(data));
    }

    private final ComputeData data;
    private final ComputeMcagDepositRepository repository;
    private final ComputeMcagAddressRepository addresses;
    private final ComputeMcagProperties config;

    @Transactional
    public Map<String, Object> bind(long memberId, String wallet, ComputeMcagProperties.Channel channel, Map<String, Object> address) {
        return bind(memberId,wallet,channel,address,null);
    }

    @Transactional
    public Map<String,Object> bind(long memberId,String wallet,ComputeMcagProperties.Channel channel,Map<String,Object> address,String lease) {
        var member = repository.bindMemberRow(memberId);
        if (enabled(member.get("isTest"))) throw new BusinessException("server.compute.mcag.testMemberRequired");
        prepareIdentity(memberId, wallet, channel);
        String customer = currentCustomer(memberId, wallet, channel);
        if (!Objects.equals(customer, address.get("customer_id")) || !Objects.equals(channel.getNetworkId(), address.get("network_id"))
                || !Objects.equals(config.getMerchantId(), address.get("merchant_id")) || !Objects.equals(config.getEnvironment(), address.get("environment"))
                || !"CUSTOMER_DEPOSIT".equals(address.get("purpose")) || !"ACTIVE".equals(address.get("status"))
                || text(address.get("memo")).length() > 120) invalid();
        ensureCurrent(customer,channel);
        var current=repository.bindMcagAddressCurrentRow(config.getMerchantId(), config.getEnvironment(), customer, channel.getAssetId());
        if(lease!=null && !lease.equals(current.get("leaseToken"))) throw new BusinessException(503,"server.compute.payment.unavailable");
        // 停用只阻止再次展示，不删除永久归属；远端仍返回旧主地址时绝不能重新启用。
        if (!addresses.disabledIds(new org.token.model.query.DepositAddressIdentity(
                config.getMerchantId(),config.getEnvironment(),memberId,wallet,channel.getNetworkId(),text(address.get("address_id")),text(address.get("address")))).isEmpty())
            throw new BusinessException(503,"server.compute.v18.depositAddress.rotationRequired");
        long binding=rememberBinding(memberId,wallet,channel,required(address,"address_id",128),required(address,"address",200),text(address.get("memo")),customer);
        boolean ready=enabled(address.get("monitoring_ready"));
        // 业务启用与链监听就绪分别保存；没有历史补查状态，也不能以 ACTIVE 代替监听核验。
        repository.bindMcagAddressCurrentWrite(binding, mappingFingerprint(channel), address.get("status"), ready, now(), current.get("id"));
        return addressView(repository.bindMcagAddressCurrentRow2(current.get("id")));
    }

    /** 历史地址仅追加归属；主地址切换不覆盖旧地址，也不使旧入账失去关联。调用方已锁用户行。 */
    long rememberBinding(long memberId,String wallet,ComputeMcagProperties.Channel channel,String addressId,String address,String memo,String customer) {
        var identity=customerIdentity(customer);
        if(identity==null || number(identity.get("memberId"))!=memberId || !wallet.equals(identity.get("walletType")))invalid();
        List<Map<String, Object>> previous = repository.rememberBindingMcagAddressRows(config.getMerchantId(), config.getEnvironment(), addressId, channel.getAssetId());
        if (previous.isEmpty()) {
            var disabled=addresses.disabledOrigin(new org.token.model.query.DepositAddressIdentity(
                    config.getMerchantId(),config.getEnvironment(),memberId,wallet,channel.getNetworkId(),addressId,address));
            return data.insert("compute_mcag_address", fields("merchant_id", config.getMerchantId(), "environment", config.getEnvironment(), "member_id", memberId,
                    "wallet_type", wallet, "customer_id", customer, "currency", channel.getCurrency(), "network", channel.getNetwork(),
                    "asset_id", channel.getAssetId(), "network_id", channel.getNetworkId(), "address_id",addressId,
                    "address",address,"memo",memo,"created_at",now(),"disabled_at",disabled.isEmpty()?null:java.time.LocalDateTime.ofInstant(java.time.Instant.parse(text(disabled.get(0).get("disabledAt"))),java.time.ZoneOffset.UTC),
                    "disabled_by",disabled.isEmpty()?null:disabled.get(0).get("disabledBy")));
        } else {
            Map<String, Object> old = previous.get(0);
            if (number(old.get("memberId"))!=memberId || !wallet.equals(old.get("walletType")) || !customer.equals(old.get("customerId")) || !Objects.equals(old.get("address"),address)
                    || !Objects.equals(old.get("currency"), channel.getCurrency()) || !Objects.equals(old.get("network"), channel.getNetwork())
                    || !Objects.equals(old.get("networkId"), channel.getNetworkId()) || !Objects.equals(text(old.get("memo")),memo)) invalid();
            return number(old.get("id"));
        }
    }

    public Map<String,Object> cached(long memberId,String wallet,ComputeMcagProperties.Channel channel) {
        var rows=repository.cachedMcagAddressCurrentRows(config.getMerchantId(), config.getEnvironment(), currentCustomer(memberId,wallet,channel), channel.getAssetId(), mappingFingerprint(channel), memberId, wallet);
        if(rows.isEmpty())return null;
        var row=rows.get(0);
        int seconds=enabled(row.get("monitoringReady"))?cacheSeconds():Math.min(2,cacheSeconds());
        if(row.get("verifiedAt")==null || !java.time.Instant.parse(text(row.get("verifiedAt"))).plusSeconds(seconds).isAfter(java.time.Instant.now()))return null;
        return addressView(row);
    }

    @Transactional
    public boolean claimRefresh(long memberId,String wallet,ComputeMcagProperties.Channel channel,String lease) {
        return claimRefresh(memberId,wallet,channel,currentCustomer(memberId,wallet,channel),lease);
    }

    @Transactional
    public boolean claimRefresh(long memberId,String wallet,ComputeMcagProperties.Channel channel,String customer,String lease) {
        repository.claimRefreshMemberRow(memberId);
        prepareIdentity(memberId,wallet,channel);
        if(!currentCustomer(memberId,wallet,channel).equals(customer))return false;
        ensureCurrent(customer,channel);
        return repository.claimRefreshMcagAddressCurrentWrite(lease, now().plusSeconds(40), config.getMerchantId(), config.getEnvironment(), customer, channel.getAssetId(), now())==1;
    }

    public void failRefresh(long memberId,String wallet,ComputeMcagProperties.Channel channel,String lease) {
        repository.failRefreshMcagAddressCurrentWrite(config.getMerchantId(), config.getEnvironment(), currentCustomer(memberId,wallet,channel), channel.getAssetId(), lease);
    }

    /** 每条真实链共享同钱包的地址代次，币种不是地址身份。 */
    public String currentCustomer(long memberId,String wallet,ComputeMcagProperties.Channel channel) {
        var rows=repository.currentCustomerMcagAddressGenerationRows(config.getMerchantId(), config.getEnvironment(), memberId, wallet, channel.getNetworkId());
        return generationCustomer(memberId,wallet,rows.isEmpty()?0:number(rows.get(0).get("generation")));
    }

    /** 调用方已锁定用户；在任何外呼前持久化客户归属，迟到回调也可精确关联。 */
    void prepareIdentity(long memberId,String wallet,ComputeMcagProperties.Channel channel) {
        repository.prepareIdentityMcagAddressGenerationWrite(config.getMerchantId(), config.getEnvironment(), memberId, wallet, channel.getNetworkId(), now());
        String customer=currentCustomer(memberId,wallet,channel);
        repository.prepareIdentityMcagCustomerIdentityWrite(config.getMerchantId(), config.getEnvironment(), customer, memberId, wallet, now());
        var identity=customerIdentity(customer);
        if(identity==null||number(identity.get("memberId"))!=memberId||!wallet.equals(identity.get("walletType")))invalid();
    }

    public Map<String,Object> customerIdentity(String customer) {
        var rows=repository.customerIdentityMcagCustomerIdentityRows(config.getMerchantId(), config.getEnvironment(), customer);
        return rows.isEmpty()?null:rows.get(0);
    }

    /** 清理会员后仍保留永久归属；迟到的旧客户回调不得重新生成用户订单。 */
    boolean removedCustomer(String customer,String asset,String network,String addressId,String address) {
        var identity=customerIdentity(customer);
        if(identity==null || repository.registeredMemberExists(number(identity.get("memberId"))))return false;
        long memberId=number(identity.get("memberId"));String wallet=text(identity.get("walletType"));
        if(memberId<=0 || !Set.of("COMPUTE","PROJECT").contains(wallet))invalid();
        // 地址可能在清理前已由远端分配、尚未回写本地；此时只认可持久客户身份，不从字符串猜UID。
        var bindings=repository.rememberBindingMcagAddressRows(config.getMerchantId(),config.getEnvironment(),addressId,asset);
        if(bindings.size()>1)invalid();
        if(!bindings.isEmpty()) {
            var binding=bindings.get(0);
            if(number(binding.get("memberId"))!=memberId || !wallet.equals(binding.get("walletType"))
                    || !customer.equals(binding.get("customerId")) || !network.equals(binding.get("networkId"))
                    || !address.equals(binding.get("address")))invalid();
        }
        return true;
    }

    static String generationCustomer(long memberId,String wallet,long generation) {
        if(generation<0||generation>1_000_000_000L)throw new BusinessException("server.compute.invalidInput");
        return customer(memberId,wallet)+(generation==0?"":"_"+generation);
    }


    private void ensureCurrent(String customer,ComputeMcagProperties.Channel channel) {
        repository.ensureCurrentMcagAddressCurrentWrite(config.getMerchantId(), config.getEnvironment(), customer, channel.getAssetId());
    }
    int cacheSeconds() { return Math.max(1,Math.min(300,config.getAddressCacheSeconds())); }
    static String mappingFingerprint(ComputeMcagProperties.Channel channel) {
        return org.token.service.compute.ComputeCredentials.digest(String.join("|",channel.getCurrency(),channel.getNetwork(),channel.getAssetId(),channel.getNetworkId(),String.valueOf(channel.getMappingVersion())));
    }
    private static Map<String,Object> addressView(Map<String,Object> row) {
        boolean ready=enabled(row.get("monitoringReady")) && "ACTIVE".equals(row.get("addressStatus"));
        String status=text(row.get("addressStatus"));
        return fields("address",ready?row.get("address"):"","memo",ready?text(row.get("memo")):"","addressId",row.get("addressId"),
                "currency",row.get("currency"),"network",row.get("network"),"walletType",row.get("walletType"),"monitoringReady",ready,
                "status",status,"verifiedAt",row.get("verifiedAt"));
    }

    public static String customer(long memberId, String wallet) {
        if (memberId <= 0 || !Set.of("COMPUTE", "PROJECT").contains(wallet)) throw new BusinessException("server.compute.depositWalletInvalid");
        return "token_" + memberId + "_" + wallet.toLowerCase(Locale.ROOT);
    }

    @Transactional
    public void ignore(Map<String, Object> event) { tenant(event); recordEvent(event, "IGNORED"); }
    @Transactional
    public void recordPayoutEvent(Map<String,Object> event, String state) {
        tenant(event);
        if (!Set.of("IGNORED", "STALE", "SUBMITTED", "REVIEW", "SUCCEEDED", "RELEASED").contains(state)) invalid();
        recordEvent(event, state);
    }
    @Transactional
    public void recordCollectionEvent(Map<String,Object> event, String state) {
        if (event == null) return;
        tenant(event);
        if (!Set.of("CREDITED", "REVIEW", "STALE", "IGNORED", "TRACKED").contains(state)) invalid();
        recordEvent(event, state);
    }
    private void recordEvent(Map<String, Object> event, String state) {
        if (event == null) return;
        List<Map<String,Object>> previous = repository.recordEventMcagEventRows(config.getMerchantId(), config.getEnvironment(), required(event, "event_id", 128));
        if (!previous.isEmpty()) {
            Map<String,Object> old = previous.get(0);
            if (number(old.get("eventSequence")) != number(event.get("event_sequence")) || !Objects.equals(old.get("resourceId"), event.get("resource_id"))
                    || number(old.get("resourceVersion")) != number(event.get("resource_version"))) invalid();
            if (!text(old.get("eventType")).isEmpty() && !Objects.equals(old.get("eventType"), text(event.get("event_type")))
                    || !text(old.get("resourceType")).isEmpty() && !Objects.equals(old.get("resourceType"), text(event.get("resource_type")))) invalid();
            return;
        }
        if (number(event.get("event_sequence")) < 1 || number(event.get("resource_version")) < 1) invalid();
        repository.recordEventMcagEventWrite(config.getMerchantId(), config.getEnvironment(), required(event, "event_id", 128), number(event.get("event_sequence")), required(event, "resource_id", 128), number(event.get("resource_version")), state, now(), optional(event, "event_type", 80), optional(event, "resource_type", 80));
        var stored = repository.recordEventMcagEventRow(config.getMerchantId(), config.getEnvironment(), number(event.get("event_sequence")));
        if (!Objects.equals(stored.get("eventId"), event.get("event_id")) || !Objects.equals(stored.get("resourceId"), event.get("resource_id"))
                || number(stored.get("resourceVersion")) != number(event.get("resource_version"))) invalid();
    }
    private void ensureCursor() {
        repository.ensureCursorMcagCursorWrite(config.getMerchantId(), config.getEnvironment(), now());
    }
    public long cursor() { ensureCursor(); return repository.cursorMcagCursorValue(config.getMerchantId(), config.getEnvironment()); }
    public void advance(long previous, long next) {
        if (next <= previous) invalid();
        repository.advanceMcagCursorWrite(next, now(), config.getMerchantId(), config.getEnvironment(), previous);
    }
    private void tenant(Map<String, Object> value) {
        if (!"LIVE".equals(config.getEnvironment()) || !Objects.equals(config.getMerchantId(), value.get("merchant_id")) || !Objects.equals(config.getEnvironment(), value.get("environment"))) invalid();
    }
    static String required(Map<String, Object> value, String key, int length) {
        String result = text(value.get(key)); if (result.isEmpty() || result.length() > length) invalid(); return result;
    }
    private static String optional(Map<String,Object> value, String key, int length) {
        String result = text(value.get(key)); if (result.length() > length) invalid(); return result;
    }
    private static void invalid() { throw new BusinessException(502, "server.compute.payment.invalidResponse"); }
}
