package org.token.service.compute;

import org.token.repository.compute.ComputeMcagAddressRepository;

import org.token.repository.compute.ComputeDepositAddressManagementRepository;

import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputePaymentGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 充值地址永久归属的后台目录；外部生成使用原支付网关，查询与停用不依赖远端。 */
@Service
public class ComputeDepositAddressManagementService {
    private final ComputeData data;
    private final ComputeDepositAddressManagementRepository repository;
    private final ComputeMcagAddressRepository addresses;
    private final ComputeAgentScope scope;
    private final ComputeMcagProperties config;
    private final ComputeMcagChannelService channels;
    private final ComputeCurrencyService currencies;
    private final ComputePaymentGateway gateway;
    private final TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeDepositAddressManagementService(ComputeData data,ComputeAgentScope scope,ComputeMcagProperties config,
            ComputeMcagChannelService channels,ComputeCurrencyService currencies,ComputePaymentGateway gateway,PlatformTransactionManager manager,
            ComputeDepositAddressManagementRepository repository,ComputeMcagAddressRepository addresses) {
        this.data=data;this.scope=scope;this.config=config;this.channels=channels;this.currencies=currencies;this.gateway=gateway;
        this.transactions=new TransactionTemplate(manager);

        this.repository = repository;
        this.addresses = addresses;
    }

    public ComputeDepositAddressManagementService(ComputeData data,ComputeAgentScope scope,ComputeMcagProperties config,
            ComputeMcagChannelService channels,ComputeCurrencyService currencies,ComputePaymentGateway gateway,PlatformTransactionManager manager,ComputeDepositAddressManagementRepository repository) {
        this(data,scope,config,channels,currencies,gateway,manager,repository,new ComputeMcagAddressRepository(data));
    }

    public ComputeDepositAddressManagementService(ComputeData data,ComputeAgentScope scope,ComputeMcagProperties config,
            ComputeMcagChannelService channels,ComputeCurrencyService currencies,ComputePaymentGateway gateway,PlatformTransactionManager manager) {
        this(data, scope, config, channels, currencies, gateway, manager, new ComputeDepositAddressManagementRepository(data));
    }

    public Map<String,Object> page(int page,int size,String username,String network,String status) {
        return page(page,size,username,network,status,null,null);
    }

    public Map<String,Object> page(int page,int size,String username,String network,String status,String address,String currency) {
        return repository.page(config.getMerchantId(),config.getEnvironment(),scope.queryScope(),page,size,username,network,status,address,currency);
    }

    /** 查询选项取自授权范围内的永久地址，不受当前通道启停及网关可用性影响。 */
    public List<Map<String,Object>> filterOptions() {
        return repository.filterOptions(config.getMerchantId(),config.getEnvironment(),scope.queryScope());
    }

    /** 只提供当前启用的真实充值链；币种由后台固定排序选择，地址仍按钱包及远端链归属。 */
    public List<Map<String,Object>> networks(String wallet) {
        validateWallet(wallet);
        var result=new LinkedHashMap<String,Map<String,Object>>();
        Set<String> evm=evmNetworks();
        for(var channel:availableChannels(wallet)) {String network=evm.contains(channel.getNetwork())?"EVM":channel.getNetwork();result.putIfAbsent(network,fields("network",network));}
        return new ArrayList<>(result.values());
    }

    public Map<String,Object> generate(String username,String network,String wallet) {
        validateWallet(wallet);
        String name=limited(username,64),chain=limited(network,32);
        if(name.isEmpty()||chain.isEmpty())invalid();
        // 代理关系锁及用户状态复核只覆盖本地校验；网络外呼不持有资金或关系事务。
        long member=Objects.requireNonNull(transactions.execute(ignored->{
            scope.relations().lock();
            long id=number(repository.generateMemberRow(name).get("id"));
            scope.assertActiveMember(id);
            var row=repository.generateMemberRow2(id);
            if(enabled(row.get("isTest")))throw new BusinessException("server.compute.mcag.testMemberRequired");
            return id;
        }));
        Set<String> evm=evmNetworks();
        var candidates=availableChannels(wallet).stream().filter(item->chain.equals(item.getNetwork())||"EVM".equals(chain)&&evm.contains(item.getNetwork())).toList();
        // 优先已登记的有效EVM链，避免目录合并后从另一链额外创建同一用户地址。
        var known=repository.generateMcagAddressRows(config.getMerchantId(), config.getEnvironment(), member, wallet);
        var existing=known.stream().filter(row->row.get("disabledAt")==null).map(row->text(row.get("networkId"))).collect(java.util.stream.Collectors.toSet());
        var historical=known.stream().map(row->text(row.get("networkId"))).collect(java.util.stream.Collectors.toSet());
        var channel=candidates.stream().sorted(Comparator.comparingInt(item->existing.contains(item.getNetworkId())?0:historical.contains(item.getNetworkId())?1:2)).findFirst()
                .orElseThrow(()->new BusinessException("server.compute.currency.depositDisabled"));
        // 沿用同 customer_id 的 get-or-create 与数据库租约，即使缓存过期也不会新建第二份有效归属。
        var result=gateway.getOrCreateAddress(member,new ComputePaymentGateway.Context(channel.getCurrency(),channel.getNetwork(),wallet));
        scope.assertMember(member);
        return result;
    }

    public void disable(long id) {
        transactions.executeWithoutResult(ignored->{
            scope.relations().lock();
            var row=repository.disableMcagAddressRow(id, config.getMerchantId(), config.getEnvironment());
            long member=number(row.get("memberId"));scope.assertMember(member);
            repository.disableMemberRow(member);
            // 合并EVM行停用其各链同地址；原链代次、指针失效和迟到回调保护仍逐链执行。
            var bindings=text(row.get("address")).matches("(?i)0x[0-9a-f]{40}")
                    ? repository.disableMcagAddressRows(config.getMerchantId(), config.getEnvironment(), member, row.get("walletType"), row.get("address"), row.get("customerId"))
                    : List.of(row);
            var networks=new LinkedHashMap<String,Map<String,Object>>();
            bindings.forEach(binding->networks.putIfAbsent(text(binding.get("networkId")),binding));
            networks.values().forEach(binding->disableNetwork(member,binding));
        });
    }

    private void disableNetwork(long member,Map<String,Object> row) {
            var identity=new org.token.model.query.DepositAddressIdentity(config.getMerchantId(),config.getEnvironment(),member,
                    text(row.get("walletType")),text(row.get("networkId")),text(row.get("addressId")),text(row.get("address")));
            var bindings=addresses.lockBindings(identity);
            repository.disableNetworkMcagAddressGenerationWrite(config.getMerchantId(), config.getEnvironment(), member, row.get("walletType"), row.get("networkId"), now());
            var generation=repository.disableNetworkMcagAddressGenerationRow(config.getMerchantId(), config.getEnvironment(), member, row.get("walletType"), row.get("networkId"));
            boolean current=false;
            for(var binding:bindings) if(repository.disableNetworkMcagAddressCurrentCount(binding.get("id"))>0)current=true;
            // 回调可能先补齐永久归属而尚无展示指针；没有另一有效主地址时同样推进，不能下次取回旧地址。
            if(!current) current=repository.disableNetworkMcagAddressCurrentCount2(config.getMerchantId(), config.getEnvironment(), member, row.get("walletType"), row.get("networkId"), row.get("customerId"))==0;
            // 同一远端地址在不同币种下的本地记录一起停用，不撤销充值到账历史及提款地址排除归属。
            addresses.disableBindings(identity,now(),scope.currentAdminUid());
            for(var binding:bindings) repository.disableNetworkMcagAddressCurrentWrite(binding.get("id"));
            long previous=number(generation.get("generation"));
            if(current && ComputeMcagDepositService.generationCustomer(member,text(row.get("walletType")),previous).equals(row.get("customerId"))) {
                ComputeMcagDepositService.generationCustomer(member,text(row.get("walletType")),previous+1);
                repository.disableNetworkMcagAddressGenerationWrite2(previous+1, now(), config.getMerchantId(), config.getEnvironment(), member, row.get("walletType"), row.get("networkId"));
            }
    }

    private Set<String> evmNetworks() {
        return repository.evmNetworksCurrencyNetworkRows().stream()
                .map(row->text(row.get("networkCode"))).collect(java.util.stream.Collectors.toSet());
    }

    private List<ComputeMcagProperties.Channel> availableChannels(String wallet) {
        var result=new ArrayList<ComputeMcagProperties.Channel>();
        for(var channel:channels.listChannels()) {
            if(!channel.isDepositEnabled())continue;
            try {currencies.requireChannel(channel.getCurrency(),channel.getNetwork(),"DEPOSIT",wallet,null);result.add(channel);}
            catch(BusinessException unavailable) { /* 已关闭或缺失的本地币网不能出现在可生成列表。 */ }
        }
        return result;
    }
    private static String limited(String value,int length) { String normalized=text(value);if(normalized.length()>length)invalid();return normalized; }
    private static void validateWallet(String wallet) { if(!Set.of("COMPUTE","PROJECT").contains(text(wallet)))invalid(); }
    private static void invalid() { throw new BusinessException("server.compute.invalidInput"); }
}
