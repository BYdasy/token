package org.token.service.compute;

import org.token.repository.compute.ComputeMcagConfigSyncRepository;

import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 配置同步只维护公开币网元数据和映射，远端请求始终在数据库事务之外执行。 */
@Service
public class ComputeMcagConfigSyncService {
    private static final String INVALID = "server.compute.v15.currencySyncInvalid";
    private static final String CONFLICT = "server.compute.v15.currencySyncConflict";
    private static final String SUPERSEDED = "server.compute.v15.currencySyncSuperseded";
    private static final List<String> METADATA = List.of("asset_id", "symbol", "network_id", "asset_type", "decimals",
            "contract_address", "token_standard", "network_name", "family", "chain_id", "native_asset_id", "native_symbol",
            "native_decimals", "finality_type", "required_confirmations", "configuration_version", "network_configuration_version",
            "deposit_enabled", "payout_enabled", "automatic_payout_enabled", "minimum_deposit", "minimum_payout", "ignore_amount",
            "sweep_threshold", "maximum_payout", "maximum_payout_action", "platform_fee", "network_fee_charge_cap", "fee_policy",
            "accounting_model", "payout_funding", "single_payout_review_limit_usdt", "daily_payout_review_limit_usdt", "daily_limit_timezone");
    private final ComputeData data;
    private final ComputeMcagConfigSyncRepository repository;
    private final ComputeMcagProperties config;
    private final ObjectProvider<ComputeMcagClient> clients;
    private final ComputeAgentScope scope;
    private final TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeMcagConfigSyncService(ComputeData data, ComputeMcagProperties config, ObjectProvider<ComputeMcagClient> clients,
                                       ComputeAgentScope scope, PlatformTransactionManager manager, ComputeMcagConfigSyncRepository repository) {
        this.data=data;this.config=config;this.clients=clients;this.scope=scope;
        this.transactions=new TransactionTemplate(manager);

        this.repository = repository;
    }

    public ComputeMcagConfigSyncService(ComputeData data, ComputeMcagProperties config, ObjectProvider<ComputeMcagClient> clients,
                                       ComputeAgentScope scope, PlatformTransactionManager manager) {
        this(data, config, clients, scope, manager, new ComputeMcagConfigSyncRepository(data));
    }

    public Map<String,Object> status() {
        scope.requireGlobalScope();
        var rows=repository.statusMerchantSnapshotRows(config.getMerchantId(), config.getEnvironment());
        var snapshot=rows.isEmpty()?Map.<String,Object>of():rows.get(0);
        var mappings=repository.statusMcagChannelRows(config.getMerchantId(), config.getEnvironment());
        mappings.forEach(row->{for(String key:List.of("active","depositEnabled","payoutEnabled"))row.put(key,enabled(row.get(key)));});
        return fields("enabled",config.isEnabled(),"syncedAt",snapshot.get("lastSuccessAt"),"lastAttemptAt",snapshot.get("lastAttemptAt"),
                "errorKey",snapshot.get("errorKey"),"mappings",mappings);
    }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Map<String,Object> synchronize() {
        scope.requireGlobalScope();
        var client=clients.getIfAvailable();
        if(!config.isEnabled()||client==null)throw new BusinessException(503,"server.compute.gateway.notConfigured");
        String merchant=config.getMerchantId(),environment=config.getEnvironment();
        LocalDateTime attempt=transactions.execute(tx->{
            repository.synchronizeMerchantSnapshotWrite(merchant, environment);
            return utc(repository.synchronizeMerchantSnapshotRow(merchant, environment).get("lastAttemptAt"));
        });
        try {
            var assets=validateAssets(client.request("assets/query",Map.of()));
            transactions.executeWithoutResult(tx->{
                if(!Objects.equals(merchant,config.getMerchantId())||!Objects.equals(environment,config.getEnvironment())||!config.isEnabled())throw new BusinessException(SUPERSEDED);
                // 与原币网编辑使用同一把配置锁；请求新旧顺序另外由快照微秒标记校验。
                repository.synchronizeSettingRows();
                var snapshot=repository.synchronizeMerchantSnapshotRow2(merchant, environment);
                if(!Objects.equals(attempt,utc(snapshot.get("lastAttemptAt"))))throw new BusinessException(SUPERSEDED);
                boolean initialized=snapshot.get("snapshotJson")!=null&&enabled(data.jsonMap(snapshot.get("snapshotJson")).get("mappingInitialized"));
                apply(merchant,environment,assets,!initialized);
                repository.synchronizeMerchantSnapshotWrite2(data.json(fields("items",assets,"mappingInitialized",true)), merchant, environment);
            });
            return status();
        } catch(RuntimeException error) {
            String key=error instanceof BusinessException business&&business.getMessage().startsWith("server.")?business.getMessage():"server.compute.v15.currencySyncFailed";
            repository.synchronizeMerchantSnapshotWrite3(key, merchant, environment, attempt);
            if(error instanceof BusinessException business)throw business;
            throw new BusinessException("server.compute.v15.currencySyncFailed");
        }
    }

    private void apply(String merchant,String environment,List<Map<String,Object>> remote,boolean bootstrap) {
        var mappings=repository.applyMcagChannelRows(merchant, environment);
        var assets=repository.applyCurrencyAssetRows();
        var networks=repository.applyCurrencyNetworkRows();
        var channels=repository.applyCurrencyRows();
        var plans=plan(remote,mappings,networks,bootstrap?new ArrayList<>(config.getChannels().values()):List.of());
        var seen=new HashSet<String>();var doneNetworks=new HashSet<String>();
        for(var plan:plans) {
            var row=plan.remote();String currency=plan.currency(),network=plan.network();
            String assetId=text(row.get("asset_id")),networkId=text(row.get("network_id"));
            seen.add(assetId+":"+networkId);
            var asset=find(assets,"assetCode",currency);
            if(asset==null) {
                data.insert("compute_currency_asset",fields("asset_code",currency,"asset_name","USDT0".equals(currency)?"USDT":currency,"asset_type","CRYPTO",
                        "icon_url",ComputeCurrencyIcons.asset(currency),"amount_scale",8,"enabled",false,"created_at",now(),"updated_at",now()));
                asset=fields("assetCode",currency,"assetType","CRYPTO","amountScale",8);assets.add(asset);
            } else if(!"CRYPTO".equals(asset.get("assetType")))throw new BusinessException(CONFLICT);
            var chain=find(networks,"networkCode",network);
            if(doneNetworks.add(network)) {
                if(chain==null) {
                    data.insert("compute_currency_network",fields("network_code",network,"network_name",label(row.get("network_name")),"network_type","CRYPTO",
                            "icon_url",ComputeCurrencyIcons.network(networkId),
                            "chain_type",chainType(row),"native_asset_code",row.get("native_symbol"),"confirm_blocks",integer(row.get("required_confirmations"),0,Integer.MAX_VALUE),
                            "enabled",false,"created_at",now(),"updated_at",now()));
                } else {
                    if(!"CRYPTO".equals(chain.get("networkType")))throw new BusinessException(CONFLICT);
                    repository.applyCurrencyNetworkWrite(chainType(row), row.get("native_symbol"), integer(row.get("required_confirmations"),0,Integer.MAX_VALUE), now(), network);
                }
            }
            var local=channels.stream().filter(c->currency.equals(c.get("currency"))&&network.equals(c.get("network"))).findFirst().orElse(null);
            int scale=Math.min(integer(row.get("decimals"),0,30),Math.min(8,integer(asset.get("amountScale"),0,8)));
            if(local==null) {
                data.insert("compute_currency",fields("currency",currency,"network",network,"enabled",false,"deposit_enabled",false,"withdraw_enabled",false,
                        "token_standard",text(row.get("token_standard")),"token_contract",text(row.get("contract_address")),"amount_scale",scale,
                        "deposit_min",platformMinimum(row.get("minimum_deposit"),scale),"withdraw_min",platformMinimum(row.get("minimum_payout"),scale),"created_at",now(),"updated_at",now()));
            } else {
                scale=Math.min(scale,integer(local.get("amountScale"),0,8));
                repository.applyCurrencyWrite(text(row.get("token_standard")), text(row.get("contract_address")), scale, now(), local.get("id"));
            }
            var old=mappings.stream().filter(m->assetId.equals(m.get("assetId"))&&networkId.equals(m.get("networkId"))).findFirst().orElse(null);
            String json=data.json(row);
            boolean changed=old==null||!enabled(old.get("active"))||!canonical(data.jsonMap(old.get("metadataJson"))).equals(row);
            if(old==null) data.insert("compute_mcag_channel",fields("merchant_id",merchant,"environment",environment,"currency",currency,"network",network,
                    "asset_id",assetId,"network_id",networkId,"decimals",integer(row.get("decimals"),0,30),"active",true,"deposit_enabled",row.get("deposit_enabled"),"payout_enabled",row.get("payout_enabled"),
                    "mapping_version",1,"remote_version",longValue(row.get("configuration_version")),"metadata_json",json,"synced_at",now(),"created_at",now(),"updated_at",now()));
            else repository.applyMcagChannelWrite(integer(row.get("decimals"),0,30), row.get("deposit_enabled"), row.get("payout_enabled"), changed?1:0, longValue(row.get("configuration_version")), json, now(), now(), old.get("id"));
        }
        for(var old:mappings)if(!seen.contains(text(old.get("assetId"))+":"+text(old.get("networkId"))))
            repository.applyMcagChannelWrite2(enabled(old.get("active"))?1:0, now(), now(), old.get("id"));
    }

    record Plan(String currency,String network,Map<String,Object> remote) { }

    /** 先完整规划并检测歧义，再写入；现有映射身份不能被远端同名资产替换。 */
    static List<Plan> plan(List<Map<String,Object>> remote,List<Map<String,Object>> existing,List<Map<String,Object>> networks,List<ComputeMcagProperties.Channel> seeds) {
        var networkMap=new HashMap<String,String>();var localNetworks=new HashMap<String,String>();
        for(var old:existing) bindNetwork(networkMap,localNetworks,text(old.get("networkId")),text(old.get("network")));
        for(var seed:seeds)for(var row:remote)if(Objects.equals(seed.getAssetId(),row.get("asset_id"))) {
            if(!Objects.equals(seed.getNetworkId(),row.get("network_id"))||!sameCurrencyIdentity(seed.getCurrency(),row))throw new BusinessException(CONFLICT);
            bindNetwork(networkMap,localNetworks,seed.getNetworkId(),localCode(seed.getNetwork(),32));
        }
        // Base 与 Ethereum 共用 ETH，首次同步只能使用精确链编号；已有人工或历史映射仍保留。
        for(var row:remote) if("BASE_MAINNET".equals(row.get("network_id"))&&!networkMap.containsKey("BASE_MAINNET"))
            bindNetwork(networkMap,localNetworks,"BASE_MAINNET","BASE_MAINNET");
        // 先解析有确切网络编码或原生资产依据的全部网络，避免按远端排序猜测无原生币的历史网络。
        for(var row:remote) {
            String remoteId=text(row.get("network_id"));if(networkMap.containsKey(remoteId))continue;
            var exact=networks.stream().filter(n->remoteId.equals(n.get("networkCode"))).toList();
            var strong=exact.isEmpty()?networks.stream().filter(n->"CRYPTO".equals(n.get("networkType"))&&chainType(row).equals(n.get("chainType"))
                    &&Objects.equals(row.get("native_symbol"),n.get("nativeAssetCode"))
                    &&!localNetworks.containsKey(text(n.get("networkCode")))&&uniqueNativeNetwork(row,remote)).toList():exact;
            if(strong.size()>1)throw new BusinessException(CONFLICT);
            if(strong.size()==1)bindNetwork(networkMap,localNetworks,remoteId,text(strong.get(0).get("networkCode")));
        }
        var plans=new ArrayList<Plan>();var destinations=new HashSet<String>();
        for(var row:remote) {
            String asset=text(row.get("asset_id")),remoteNetwork=text(row.get("network_id")),currency=localCode(row.get("symbol"),16);
            if(existing.stream().anyMatch(m->asset.equals(m.get("assetId"))&&!remoteNetwork.equals(m.get("networkId"))))throw new BusinessException(CONFLICT);
            var old=existing.stream().filter(m->asset.equals(m.get("assetId"))&&remoteNetwork.equals(m.get("networkId"))).findFirst().orElse(null);
            if(old!=null) {
                if(!sameCurrencyIdentity(text(old.get("currency")),row))throw new BusinessException(CONFLICT);
                // Polygon USDT 升级为 USDT0 后 asset_id 未变；保留已经用于订单与地址的本地身份。
                currency=localCode(old.get("currency"),16);
            } else {
                var seed=seeds.stream().filter(candidate->asset.equals(candidate.getAssetId())&&remoteNetwork.equals(candidate.getNetworkId())).findFirst().orElse(null);
                if(seed!=null)currency=localCode(seed.getCurrency(),16);
            }
            String network=networkMap.get(remoteNetwork);
            if(network==null) {
                var exact=networks.stream().filter(n->remoteNetwork.equals(n.get("networkCode"))).toList();
                var candidates=exact.isEmpty()?networks.stream().filter(n->"CRYPTO".equals(n.get("networkType"))&&chainType(row).equals(n.get("chainType"))
                        && Objects.equals(row.get("native_symbol"),n.get("nativeAssetCode"))
                        && !localNetworks.containsKey(text(n.get("networkCode")))&&uniqueNativeNetwork(row,remote)).toList():exact;
                if(candidates.isEmpty()) candidates=networks.stream().filter(n->"CRYPTO".equals(n.get("networkType"))&&chainType(row).equals(n.get("chainType"))
                        && text(n.get("nativeAssetCode")).isEmpty()&&!localNetworks.containsKey(text(n.get("networkCode")))).toList();
                if(candidates.size()>1)throw new BusinessException(CONFLICT);
                if(candidates.size()==1&&text(candidates.get(0).get("nativeAssetCode")).isEmpty()
                        && remote.stream().filter(r->chainType(row).equals(chainType(r))&&!networkMap.containsKey(text(r.get("network_id")))).map(r->text(r.get("network_id"))).distinct().count()>1)
                    throw new BusinessException(CONFLICT);
                network=candidates.isEmpty()?localCode(remoteNetwork,32):text(candidates.get(0).get("networkCode"));
                bindNetwork(networkMap,localNetworks,remoteNetwork,network);
            }
            final String chosen=network;
            var localNetwork=find(networks,"networkCode",chosen);
            if(localNetwork!=null&&(!"CRYPTO".equals(localNetwork.get("networkType"))
                    ||!Set.of("", "OTHER", chainType(row)).contains(text(localNetwork.get("chainType")))
                    ||!text(localNetwork.get("nativeAssetCode")).isEmpty()&&!Objects.equals(row.get("native_symbol"),localNetwork.get("nativeAssetCode"))))throw new BusinessException(CONFLICT);
            final String chosenCurrency=currency;
            if(existing.stream().anyMatch(m->chosenCurrency.equals(m.get("currency"))&&chosen.equals(m.get("network"))&&(!asset.equals(m.get("assetId"))||!remoteNetwork.equals(m.get("networkId")))))throw new BusinessException(CONFLICT);
            if(!destinations.add(currency+":"+network))throw new BusinessException(CONFLICT);
            plans.add(new Plan(currency,network,row));
        }
        return plans;
    }

    /** 只兼容 Crypto 明确的 Polygon USDT0 升级，不按展示同名跨资产、跨链猜测。 */
    private static boolean sameCurrencyIdentity(String localCurrency,Map<String,Object> remote) {
        if(Objects.equals(localCurrency,remote.get("symbol")))return true;
        return "USDT_POLYGON".equals(remote.get("asset_id"))&&"POLYGON_MAINNET".equals(remote.get("network_id"))
                &&Set.of("USDT","USDT0").contains(localCurrency)&&Set.of("USDT","USDT0").contains(text(remote.get("symbol")));
    }

    /** 原生币同名并不能证明同一条链，多个远端链共享原生币时停止推断。 */
    private static boolean uniqueNativeNetwork(Map<String,Object> row,List<Map<String,Object>> remote) {
        return remote.stream().filter(candidate->chainType(row).equals(chainType(candidate))
                &&Objects.equals(row.get("native_symbol"),candidate.get("native_symbol")))
                .map(candidate->text(candidate.get("network_id"))).distinct().count()==1;
    }

    private static void bindNetwork(Map<String,String> remote,Map<String,String> local,String remoteId,String localId) {
        if(remote.containsKey(remoteId)&&!remote.get(remoteId).equals(localId)||local.containsKey(localId)&&!local.get(localId).equals(remoteId))throw new BusinessException(CONFLICT);
        remote.put(remoteId,localId);local.put(localId,remoteId);
    }

    static List<Map<String,Object>> validateAssets(Map<String,Object> response) {
        if(response==null||!(response.get("items") instanceof List<?> values)||values.size()>2000||Boolean.TRUE.equals(response.get("has_more")))throw new BusinessException(INVALID);
        var result=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();var symbols=new HashSet<String>();var networks=new HashMap<String,List<Object>>();
        for(Object value:values) {
            if(!(value instanceof Map<?,?> raw))throw new BusinessException(INVALID);
            var row=new LinkedHashMap<String,Object>();for(String key:METADATA)if(raw.containsKey(key))row.put(key,raw.get(key));
            String asset=identifier(row.get("asset_id"),128),network=identifier(row.get("network_id"),128),symbol=localCode(row.get("symbol"),16);
            if(!ids.add(asset)||!symbols.add(network+":"+symbol)||"EVM".equals(network))throw new BusinessException(INVALID);
            row.put("asset_id",asset);row.put("network_id",network);row.put("symbol",symbol);
            if(!Set.of("NATIVE","TOKEN").contains(text(row.get("asset_type")))||!Set.of("EVM","TRON","BITCOIN").contains(text(row.get("family"))))throw new BusinessException(INVALID);
            integer(row.get("decimals"),0,30);integer(row.get("native_decimals"),0,30);integer(row.get("required_confirmations"),1,Integer.MAX_VALUE);
            localCode(row.get("native_symbol"),16);identifier(row.get("native_asset_id"),128);
            String name=text(row.get("network_name"));if(name.isBlank()||name.length()>120||name.codePoints().anyMatch(Character::isISOControl))throw new BusinessException(INVALID);
            for(String key:List.of("deposit_enabled","payout_enabled"))if(!(row.get(key) instanceof Boolean))throw new BusinessException(INVALID);
            for(String key:List.of("configuration_version","network_configuration_version"))if(longValue(row.get(key))<1)throw new BusinessException(INVALID);
            for(String key:List.of("minimum_deposit","minimum_payout","ignore_amount","sweep_threshold")) {
                if(!row.containsKey(key)||row.get(key)==null||decimal(row.get(key)).signum()<0)throw new BusinessException(INVALID);
                row.put(key,decimal(row.get(key)).stripTrailingZeros().toPlainString());
            }
            if(text(row.get("contract_address")).length()>200||text(row.get("token_standard")).length()>32)throw new BusinessException(INVALID);
            List<Object> definition=List.of(text(row.get("family")),text(row.get("native_asset_id")),text(row.get("native_symbol")),integer(row.get("native_decimals"),0,30),text(row.get("chain_id")),
                    name,text(row.get("finality_type")),integer(row.get("required_confirmations"),1,Integer.MAX_VALUE),longValue(row.get("network_configuration_version")));
            if(networks.containsKey(network)&&!networks.get(network).equals(definition))throw new BusinessException(INVALID);
            networks.put(network,definition);result.add(canonical(row));
        }
        return result;
    }

    private static Map<String,Object> canonical(Map<String,Object> values) {
        var result=new TreeMap<String,Object>();values.forEach((key,value)->result.put(key,value instanceof Number n?new BigDecimal(n.toString()).stripTrailingZeros().toPlainString():value));return result;
    }
    private static Map<String,Object> find(List<Map<String,Object>> rows,String key,String value) {return rows.stream().filter(row->value.equals(row.get(key))).findFirst().orElse(null);}
    private static String chainType(Map<String,Object> row) {return "BITCOIN".equals(row.get("family"))?"BTC":text(row.get("family"));}
    private static String label(Object value) {String text=text(value);return text.substring(0,Math.min(100,text.length()));}
    private static String identifier(Object value,int maximum) {String text=text(value);if(text.isEmpty()||text.length()>maximum||!text.matches("[A-Za-z0-9_-]+"))throw new BusinessException(INVALID);return text;}
    private static String localCode(Object value,int maximum) {String text=identifier(value,maximum);if(!text.equals(text.toUpperCase(Locale.ROOT)))throw new BusinessException(CONFLICT);return text;}
    private static long longValue(Object value) {try {if(value==null)throw new NumberFormatException();return new BigDecimal(value.toString()).longValueExact();}catch(RuntimeException ex){throw new BusinessException(INVALID);}}
    private static int integer(Object value,int minimum,int maximum) {long result=longValue(value);if(result<minimum||result>maximum)throw new BusinessException(INVALID);return (int)result;}
    private static BigDecimal platformMinimum(Object value,int scale) {BigDecimal result=decimal(value).setScale(scale,RoundingMode.CEILING);if(result.precision()-result.scale()>20)throw new BusinessException(INVALID);return result;}
    private static LocalDateTime utc(Object value) {if(value instanceof LocalDateTime time)return time;return LocalDateTime.ofInstant(Instant.parse(text(value)),ZoneOffset.UTC);}
}
