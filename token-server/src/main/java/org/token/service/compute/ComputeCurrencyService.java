package org.token.service.compute;

import org.token.repository.compute.ComputeCurrencyRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.config.compute.ComputeMcagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 资产、网络和通道统一管理。配置不改变历史余额，也不替代支付适配器。 */
@Service
public class ComputeCurrencyService {
    private final ComputeData data;
    private final ComputeCurrencyRepository repository;
    private final ComputeMcagProperties mcag;
    private final ComputeMcagChannelService mappings;
    @Autowired
    public ComputeCurrencyService(ComputeData data, ComputeMcagProperties mcag, ComputeMcagChannelService mappings, ComputeCurrencyRepository repository) { this.data = data; this.mcag = mcag; this.mappings=mappings;
        this.repository = repository;
    }

    public ComputeCurrencyService(ComputeData data, ComputeMcagProperties mcag, ComputeMcagChannelService mappings) {
        this(data, mcag, mappings, new ComputeCurrencyRepository(data));
    }
    public ComputeCurrencyService(ComputeData data, ComputeMcagProperties mcag) { this(data,mcag,new ComputeMcagChannelService(data,mcag)); }
    public ComputeCurrencyService(ComputeData data) { this(data, new ComputeMcagProperties()); }
    public boolean gatewayEnabled() { return mcag.isEnabled(); }
    public static final Set<String> ADDRESS_FIELDS = Set.of("holderName", "bankName", "bankCode", "bankAccountNo",
            "branchName", "branchCode", "iban", "swiftBic", "ifsc", "routingNumber", "sortCode", "pixKey", "cpf",
            "clabe", "countryCode", "phone", "email");
    private static final Set<String> RECEIVING_FIELDS = Set.of("bankAccountNo", "iban", "pixKey", "clabe");
    private static final Map<String, String> TABLES = Map.of("currencies", "compute_currency",
            "currency-assets", "compute_currency_asset", "currency-networks", "compute_currency_network");

    public List<Map<String, Object>> publicChannels() {
        return normalizeNetworks(repository.publicChannels()).stream()
                .filter(row -> !"MCAG".equals(row.get("depositMode")) || enabled(row.get("gatewayActive")))
                .toList();
    }

    /** 平台统一USD计价，既有USDT钱包编码保持兼容；充提渠道不决定记账单位。 */
    public String defaultCurrency() { return ComputeUsdAccounting.WALLET_CURRENCY; }

    /** 调用方已锁用户行；仅启用且未移除地址占用名额，新增或恢复与数量检查同事务。 */
    public void assertAddressCapacity(long memberId,Long existingId) {
        if(existingId!=null && repository.assertAddressCapacityAddressCount(existingId, memberId)>0)return;
        Integer override=repository.assertAddressCapacityMemberValue(memberId);
        int limit=override==null?integer(data.setting("addressLimit","10"),1,1000):override;
        long count=repository.assertAddressCapacityAddressCount2(memberId);
        if(count>=limit)throw new BusinessException("server.compute.v6.addressLimitReached");
    }

    public Map<String,Object> manualDepositChannel(String wallet,BigDecimal amount) {
        for(var row:normalizeNetworks(repository.manualDepositChannels(defaultCurrency()))) {
            if(!Arrays.asList(text(row.get("supportAccountTypes")).split(",")).contains(wallet)||amount.compareTo(decimal(row.get("depositMin")))<0)continue;
            return row;
        }
        throw new BusinessException("server.compute.v6.depositChannelRequired");
    }

    /** 默认币种优先，其次最近更新的有效地址；返回选中时的完整收款快照。 */
    public Map<String,Object> manualWithdrawalRecipient(long memberId,BigDecimal amount) {
        for(var row:repository.manualWithdrawalRecipientAddressRows(memberId, defaultCurrency())) {
            try {
                var channel=requireChannel(text(row.get("currency")),text(row.get("network")),"WITHDRAW","EARNINGS",amount);
                if(row.get("fieldsJson")!=null)row.put("fields",data.jsonMap(row.get("fieldsJson")));
                var address=validateAddress(channel,row);
                return ComputeData.fields("currency",row.get("currency"),"network",row.get("network"),"addressId",row.get("id"),"address",address.address(),"memo",address.memo(),"fields",address.fields());
            } catch(BusinessException invalid) { /* 跳过停用通道或已失效的历史地址，不修改原记录。 */ }
        }
        throw new BusinessException("server.compute.v6.withdrawalAddressRequired");
    }

    public List<Map<String, Object>> defaultChannels() {
        String currency = defaultCurrency();
        return publicChannels().stream().filter(channel -> currency.equals(text(channel.get("currency")))).toList();
    }

    public boolean hasActiveCurrency(String currency) {
        return repository.activeCurrencyCount(currency) > 0;
    }

    public Map<String, Object> options() {
        return Map.of("assets", repository.optionsCurrencyAssetRows(),
                "networks", normalizeNetworks(repository.optionsCurrencyNetworkRows()),
                "channels", normalizeNetworks(repository.visibleChannelOptions(mcag.getMerchantId(),mcag.getEnvironment())), "addressFields", new TreeSet<>(ADDRESS_FIELDS), "defaultCurrency", defaultCurrency());
    }

    public Map<String, Object> page(String kind, Map<String, String> query) {
        var result = repository.page(kind, query,mcag.getMerchantId(),mcag.getEnvironment());
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows = (List<Map<String,Object>>) result.get("records");
        if ("currencies".equals(kind) || "currency-networks".equals(kind)) normalizeNetworks(rows);
        return result;
    }

    @Transactional
    public Map<String, Object> save(String kind, Long id, Map<String, Object> request) {
        String table = table(kind);
        // 所有通道与元数据写入使用同一把配置锁，防止并发停用最后一个基准币通道。
        repository.saveSettingRows();
        boolean hadSummaryChannel = repository.activeCurrencyCount(data.setting("summaryCurrency", "USDT")) > 0;
        Map<String, Object> old = id == null ? Map.of() : data.required(table, id, true);
        Map<String, Object> merged = new LinkedHashMap<>(old); merged.putAll(request);
        Map<String, Object> values = "currencies".equals(kind) ? channelValues(id, old, merged)
                : metadataValues(kind, id, old, merged);
        values.put("updated_at", now());
        if (id == null) values.put("created_at", now());
        try {
            if (id == null) id = data.insert(table, values);
            else {
                List<Object> args = new ArrayList<>(values.values()); args.add(id);
                data.update(table, id, values);
            }
        } catch (DuplicateKeyException exception) { throw new BusinessException("server.compute.currency.duplicate"); }
        if (hadSummaryChannel && repository.activeCurrencyCount(data.setting("summaryCurrency", "USDT")) == 0) throw new BusinessException("server.compute.summaryCurrencyInvalid");
        if ("currencies".equals(kind)) return normalizeNetworks(repository.channelById(id)).get(0);
        Map<String, Object> row = data.required(table, id, false);
        if ("currency-networks".equals(kind)) normalizeNetworks(List.of(row));
        return row;
    }

    private Map<String, Object> channelValues(Long id, Map<String, Object> old, Map<String, Object> request) {
        String currency = code(request.get("currency"), 16), network = code(request.get("network"), 32);
        if (id != null && (!currency.equals(text(old.get("currency"))) || !network.equals(text(old.get("network")))))
            throw new BusinessException("server.compute.currencyCodeImmutable");
        if(Set.of("USDT","USDT0").contains(currency)) {
            // 当前商户已有精确 Polygon 映射时，不新建或重新启用另一无映射别名。
            var syncedMappings=mappings.listSyncedChannels();
            boolean hasOwnMapping=syncedMappings.stream().anyMatch(mapped->currency.equals(mapped.getCurrency())&&network.equals(mapped.getNetwork()));
            for(var mapped:syncedMappings)if(!hasOwnMapping&&"USDT_POLYGON".equals(mapped.getAssetId())
                    &&"POLYGON_MAINNET".equals(mapped.getNetworkId())&&network.equals(mapped.getNetwork())
                    &&Set.of("USDT","USDT0").contains(mapped.getCurrency())&&!currency.equals(mapped.getCurrency())
                    &&(id==null||enabled(request.getOrDefault("enabled",true))))throw new BusinessException("server.compute.currency.duplicate");
        }
        Map<String, Object> asset = repository.channelValuesCurrencyAssetRow(currency);
        Map<String, Object> route = repository.channelValuesCurrencyNetworkRow(network);
        if (!text(asset.get("assetType")).equals(text(route.get("networkType")))) throw new BusinessException("server.compute.currency.typeMismatch");
        BigDecimal minimum = nonnegative(request.getOrDefault("withdrawMin", 0));
        BigDecimal maximum = nonnegative(request.getOrDefault("withdrawMax", 0));
        if (maximum.signum() > 0 && maximum.compareTo(minimum) < 0) throw new BusinessException("server.compute.invalidAmount");
        BigDecimal ratio = nonnegative(request.getOrDefault("withdrawFeeRate", 0));
        if (ratio.compareTo(BigDecimal.ONE) > 0) throw new BusinessException("server.compute.invalidRate");
        String wallets = text(request.getOrDefault("supportAccountTypes", "COMPUTE,PROJECT,EARNINGS"));
        TreeSet<String> supported = new TreeSet<>(Arrays.asList(wallets.split(",")));
        if (supported.isEmpty() || !Set.of("COMPUTE", "PROJECT", "EARNINGS").containsAll(supported)) invalid();
        var synced=syncedChannel(currency,network);
        int scale=integer(request.getOrDefault("amountScale",8),0,8);
        if(!synced.isEmpty())scale=Math.min(scale,integer(synced.get("decimals"),0,30));
        return fields("currency", currency, "network", network, "enabled", bool(request.getOrDefault("enabled", true)),
                "deposit_enabled", bool(request.getOrDefault("depositEnabled", true)), "withdraw_enabled", bool(request.getOrDefault("withdrawEnabled", true)),
                "deposit_min", nonnegative(request.getOrDefault("depositMin", 0)), "withdraw_min", minimum, "withdraw_max", maximum,
                "withdraw_fee_fixed", nonnegative(request.getOrDefault("withdrawFeeFixed", 0)), "withdraw_fee_rate", ratio,
                "fee_mode", choice(request.getOrDefault("feeMode", id == null ? "CHANNEL" : "ADVANCED_RULE"), Set.of("CHANNEL", "ADVANCED_RULE")),
                "token_standard", synced.isEmpty()?limited(request.get("tokenStandard"),32):text(old.get("tokenStandard")),
                "token_contract", synced.isEmpty()?limited(request.get("tokenContract"),200):text(old.get("tokenContract")),
                "support_account_types", String.join(",", supported), "need_memo", bool(request.getOrDefault("needMemo", false)),
                "amount_scale", scale, "sort_no", integer(request.getOrDefault("sortNo", 0), -100000, 100000),
                "remark", limited(request.get("remark"), 1000));
    }

    private Map<String, Object> metadataValues(String kind, Long id, Map<String, Object> old, Map<String, Object> request) {
        boolean asset = "currency-assets".equals(kind);
        String key = asset ? "assetCode" : "networkCode", name = asset ? "assetName" : "networkName", type = asset ? "assetType" : "networkType";
        String code = code(request.get(key), asset ? 16 : 32);
        if (id != null && !code.equals(text(old.get(key)))) throw new BusinessException("server.compute.currencyCodeImmutable");
        String label = limited(request.get(name), 100);
        if (label.isEmpty()) invalid();
        String kindValue = choice(request.getOrDefault(type, "CRYPTO"), Set.of("CRYPTO", "FIAT"));
        if (id != null && !kindValue.equals(old.get(type)) && repository.metadataUseCount(asset, code) > 0) throw new BusinessException("server.compute.currency.typeImmutable");
        Map<String, Object> values = fields(asset ? "asset_code" : "network_code", code, asset ? "asset_name" : "network_name", label,
                asset ? "asset_type" : "network_type", kindValue, "enabled", bool(request.getOrDefault("enabled", true)),
                "sort_no", integer(request.getOrDefault("sortNo", 0), -100000, 100000), "remark", limited(request.get("remark"), 1000));
        if (asset) {
            String rateId = "CRYPTO".equals(kindValue) ? limited(request.get("coingeckoId"), 128) : "";
            if (!rateId.isEmpty() && !rateId.matches("[a-z0-9][a-z0-9-]{0,127}"))
                throw new BusinessException("server.compute.v9.coingeckoIdInvalid");
            values.put("coingecko_id", rateId.isEmpty() ? null : rateId);
            values.put("icon_url", url(request.get("iconUrl")));
            values.put("amount_scale", integer(request.getOrDefault("amountScale", 8), 0, 8));
        } else {
            boolean synced=syncedNetworkCodes().contains(code);
            values.put("icon_url",url(request.get("iconUrl")));
            values.put("chain_type",synced?text(old.get("chainType")):code(request.getOrDefault("chainType", "OTHER"),32));
            values.put("native_asset_code",synced?text(old.get("nativeAssetCode")):text(request.get("nativeAssetCode")).isEmpty()?"":code(request.get("nativeAssetCode"),16));
            values.put("confirm_blocks",synced?integer(old.get("confirmBlocks"),0,Integer.MAX_VALUE):integer(request.getOrDefault("confirmBlocks",0),0,1000000));
            values.put("block_explorer_url", url(request.get("blockExplorerUrl")));
            List<String> required = requiredFields(request.containsKey("requiredFields") ? request.get("requiredFields") : request.get("requiredFieldsJson"));
            if ("FIAT".equals(kindValue) && (!required.contains("holderName") || required.stream().noneMatch(RECEIVING_FIELDS::contains)))
                throw new BusinessException("server.compute.currency.requiredFieldsInvalid");
            if ("CRYPTO".equals(kindValue) && !required.isEmpty()) throw new BusinessException("server.compute.currency.requiredFieldsInvalid");
            values.put("required_fields_json", data.json(required));
        }
        return values;
    }

    /** 资金请求每次读取实时配置；历史订单审核不调用此方法。 */
    public Map<String, Object> requireChannel(String currency, String network, String operation, String walletType, BigDecimal amount) {
        // 绑定的是提款收款地址，与提款共用实时开关；历史地址列表和删除不经过此入口。
        boolean withdrawal = "WITHDRAW".equals(operation) || "ADDRESS".equals(operation);
        List<Map<String, Object>> rows = normalizeNetworks(repository.channelByAssetNetwork(currency, network));
        if (rows.isEmpty()) throw new BusinessException("server.compute.unsupportedCurrency");
        Map<String, Object> row = rows.get(0);
        if (!enabled(row.get("enabled")) || !enabled(row.get("assetEnabled")) || !enabled(row.get("networkEnabled")))
            throw new BusinessException("server.compute.unsupportedCurrency");
        if (mcag.isEnabled() && "CRYPTO".equals(row.get("assetType"))) {
            var mapped=mappings.requireChannel(currency,network);
            if(!mapped.isActive())throw new BusinessException("server.compute.unsupportedCurrency");
            if("DEPOSIT".equals(operation)&&!mapped.isDepositEnabled())throw new BusinessException("server.compute.currency.depositDisabled");
            if(withdrawal&&!mapped.isPayoutEnabled())throw new BusinessException("server.compute.currency.withdrawDisabled");
        }
        if ("DEPOSIT".equals(operation) && !enabled(row.get("depositEnabled"))) throw new BusinessException("server.compute.currency.depositDisabled");
        if (withdrawal && !enabled(row.get("withdrawEnabled"))) throw new BusinessException("server.compute.currency.withdrawDisabled");
        if (walletType != null && !Arrays.asList(text(row.get("supportAccountTypes")).split(",")).contains(walletType))
            throw new BusinessException("server.compute.currency.walletUnsupported");
        if (amount != null) {
            BigDecimal min = decimal(row.get("DEPOSIT".equals(operation) ? "depositMin" : "withdrawMin"));
            BigDecimal max = "DEPOSIT".equals(operation) ? BigDecimal.ZERO : decimal(row.get("withdrawMax"));
            if (amount.compareTo(min) < 0 || max.signum() > 0 && amount.compareTo(max) > 0) throw new BusinessException("server.compute.currency.amountRange");
        }
        return row;
    }

    public static BigDecimal channelFee(Map<String, Object> channel, BigDecimal amount) {
        BigDecimal fee = amount.multiply(decimal(channel.get("withdrawFeeRate"))).add(decimal(channel.get("withdrawFeeFixed"))).setScale(8, RoundingMode.UP);
        if (fee.signum() < 0 || fee.compareTo(amount) >= 0) throw new BusinessException("server.compute.feeExceedsAmount");
        return fee;
    }

    public record AddressData(String address, String memo, Map<String, String> fields) {
        public Map<String, Object> snapshot() { return ComputeData.fields("address", address, "memo", memo, "fields", fields); }
    }

    /** 将同一通道的收款信息归一化，字段白名单不会允许任意 JSON 进入付款快照。 */
    public AddressData validateAddress(Map<String, Object> channel, Map<String, Object> input) {
        String memo = limited(input.get("memo"), 120);
        Map<String, String> fields = new LinkedHashMap<>();
        Object raw = input.get("fields");
        if (raw instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = text(entry.getKey());
                if (!ADDRESS_FIELDS.contains(key)) throw new BusinessException("server.compute.currency.requiredFieldsInvalid");
                String value = limited(entry.getValue(), 250);
                if (!value.isEmpty()) fields.put(key, value);
            }
        } else if (raw != null) invalid();
        if (enabled(channel.get("needMemo")) && memo.isEmpty()) throw new BusinessException("server.compute.currency.memoRequired");
        if (!enabled(channel.get("needMemo")) && !memo.isEmpty()) throw new BusinessException("server.compute.invalidAddress");
        String address;
        if ("FIAT".equals(channel.get("assetType"))) {
            for (String key : requiredFields(channel.get("requiredFields"))) if (!fields.containsKey(key))
                throw new BusinessException("server.compute.currency.addressFieldsRequired");
            address = List.of("bankAccountNo", "iban", "pixKey", "clabe").stream().map(fields::get).filter(Objects::nonNull).findFirst().orElse("");
            if (address.isEmpty() || !fields.containsKey("holderName")) throw new BusinessException("server.compute.currency.addressFieldsRequired");
            address = limited(address, 200);
        } else {
            if (!fields.isEmpty()) throw new BusinessException("server.compute.currency.requiredFieldsInvalid");
            address = limited(input.get("address"), 200);
            String chain = text(channel.get("chainType"));
            boolean valid = "TRON".equals(chain) ? address.matches("T[1-9A-HJ-NP-Za-km-z]{33}")
                    : "EVM".equals(chain) ? address.matches("0x[0-9a-fA-F]{40}")
                    : Set.of("BTC", "BITCOIN").contains(chain) ? address.matches("[13][1-9A-HJ-NP-Za-km-z]{25,34}|bc1[ac-hj-np-z02-9]{11,71}|BC1[AC-HJ-NP-Z02-9]{11,71}")
                    : address.matches("[A-Za-z0-9:_-]{16,200}");
            if (!valid) throw new BusinessException("server.compute.invalidAddress");
        }
        return new AddressData(address, memo, Collections.unmodifiableMap(fields));
    }

    /** 同步归属不因远端临时停用或接入开关关闭而解除，历史映射仍保护链上元数据。 */
    private Map<String,Object> syncedChannel(String currency,String network) {
        if(text(mcag.getMerchantId()).isEmpty())return Map.of();
        var rows=repository.syncedChannelMcagChannelRows(mcag.getMerchantId(), mcag.getEnvironment(), currency, network);
        if(rows.size()>1)throw new BusinessException("server.compute.mcag.channelMismatch");
        return rows.isEmpty()?Map.of():rows.get(0);
    }
    private Set<String> syncedNetworkCodes() {
        if(text(mcag.getMerchantId()).isEmpty())return Set.of();
        var result=new HashSet<String>();
        repository.syncedNetworkCodesMcagChannelRows(mcag.getMerchantId(), mcag.getEnvironment())
                .forEach(row->result.add(text(row.get("network"))));
        return result;
    }
    private List<Map<String, Object>> normalizeNetworks(List<Map<String, Object>> rows) {
        Map<String,ComputeMcagProperties.Channel> byChannel=new HashMap<>();
        Set<String> syncedNetworks=rows.stream().anyMatch(row->!row.containsKey("currency"))?syncedNetworkCodes():Set.of();
        if(rows.stream().anyMatch(row->row.containsKey("currency")))
            (mcag.isEnabled()?mappings.listChannels():mappings.listSyncedChannels())
                    .forEach(channel->byChannel.put(channel.getCurrency()+":"+channel.getNetwork(),channel));
        rows.forEach(row -> {
            row.put("requiredFields", requiredFields(row.remove("requiredFieldsJson")));
            if(!row.containsKey("currency"))row.put("gatewaySynced",syncedNetworks.contains(text(row.get("networkCode"))));
            if (row.containsKey("currency")) {
                row.put("assetCode", row.get("currency")); row.put("networkCode", row.get("network"));
                var mapped=byChannel.get(text(row.get("currency"))+":"+text(row.get("network")));
                String mode = mcag.isEnabled() && "CRYPTO".equals(row.get("assetType")) ? "MCAG" : "MANUAL";
                row.put("depositMode", mode); row.put("payoutMode", mode);
                row.put("gatewaySynced",mapped!=null);
                row.put("gatewayActive",mapped!=null&&mapped.isActive());
                if(mapped!=null)row.put("chainDecimals",mapped.getDecimals());
                if("MCAG".equals(mode)) {
                    row.put("localDepositEnabled",row.get("depositEnabled")); row.put("localWithdrawEnabled",row.get("withdrawEnabled"));
                    row.put("depositEnabled",enabled(row.get("depositEnabled"))&&mapped!=null&&mapped.isDepositEnabled());
                    row.put("withdrawEnabled",enabled(row.get("withdrawEnabled"))&&mapped!=null&&mapped.isPayoutEnabled());
                    if(mapped!=null) {
                        row.put("gatewayAssetId",mapped.getAssetId());row.put("gatewayNetworkId",mapped.getNetworkId());row.put("mappingVersion",mapped.getMappingVersion());
                        row.put("localDepositMin",row.get("depositMin"));row.put("localWithdrawMin",row.get("withdrawMin"));
                        int scale=Math.min(8,Math.min(mapped.getDecimals(),(int)number(row.get("amountScale"))));
                        row.put("depositMin",decimal(row.get("depositMin")).max(mapped.getMinimumDeposit()).setScale(scale,RoundingMode.CEILING).toPlainString());
                        row.put("withdrawMin",decimal(row.get("withdrawMin")).max(mapped.getMinimumPayout()).setScale(scale,RoundingMode.CEILING).toPlainString());
                    }
                }
            }
        });
        return rows;
    }
    private List<String> requiredFields(Object raw) {
        if (raw == null || text(raw).isEmpty()) return List.of();
        if (raw instanceof byte[] bytes) raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (raw instanceof String value) {
            try { raw = data.jsonMap("{\"fields\":" + value + "}").get("fields"); }
            catch (BusinessException exception) { throw new BusinessException("server.compute.currency.requiredFieldsInvalid"); }
        }
        if (!(raw instanceof List<?> list) || list.size() > ADDRESS_FIELDS.size()) throw new BusinessException("server.compute.currency.requiredFieldsInvalid");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object value : list) { String key = text(value); if (!ADDRESS_FIELDS.contains(key)) throw new BusinessException("server.compute.currency.requiredFieldsInvalid"); values.add(key); }
        return new ArrayList<>(values);
    }
    private static String table(String kind) { String table = TABLES.get(kind); if (table == null) throw new BusinessException("server.compute.notFound"); return table; }
    private static String code(Object value, int length) { String code = text(value).toUpperCase(Locale.ROOT); if (code.length() < 2 || code.length() > length || !code.matches("[A-Z0-9_-]+")) invalid(); return code; }
    private static String limited(Object value, int maximum) { String text = text(value); if (text.length() > maximum || text.codePoints().anyMatch(c -> Character.isISOControl(c))) invalid(); return text; }
    private static String choice(Object value, Set<String> choices) { String text = text(value); if (!choices.contains(text)) invalid(); return text; }
    private static boolean bool(Object value) { if (!Set.of("true","false","1","0").contains(text(value).toLowerCase(Locale.ROOT))) invalid(); return enabled(value); }
    private static int integer(Object value, int minimum, int maximum) { long number = number(value); if (number < minimum || number > maximum) invalid(); return (int) number; }
    private static BigDecimal nonnegative(Object value) { BigDecimal valueAsNumber = decimal(value); if (valueAsNumber.signum() < 0 || valueAsNumber.scale() > 8 || valueAsNumber.precision() - valueAsNumber.scale() > 18) throw new BusinessException("server.compute.invalidAmount"); return valueAsNumber; }
    private static String url(Object value) {
        String url = limited(value, 1000);
        if (url.isEmpty() || url.startsWith("/") && !url.startsWith("//")) return url;
        try { URI parsed = URI.create(url); if (!Set.of("http", "https").contains(parsed.getScheme()) || parsed.getHost() == null || parsed.getUserInfo() != null) invalid(); }
        catch (IllegalArgumentException exception) { invalid(); }
        return url;
    }
    private static void invalid() { throw new BusinessException("server.compute.invalidInput"); }
}
