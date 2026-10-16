package org.token.service.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.token.config.compute.ComputeMcagProperties;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 访问已授权的官方或兼容汇率接口；密钥只放请求头，不跟随重定向。 */
@Component
public class ComputeExchangeRateClient {
    public record Quote(String code, BigDecimal rate, String source, String sourceId, Instant sourceAt) { }
    private final ObjectMapper mapper;
    private final ComputeExchangeProviderSettings settings;
    private final ObjectProvider<ComputeMcagClient> gateways;
    private final ComputeMcagProperties mcag;
    private final ComputeMcagChannelService mappings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeExchangeRateClient(ObjectMapper mapper, ComputeExchangeProviderSettings settings,
            ObjectProvider<ComputeMcagClient> gateways, ComputeMcagProperties mcag, ComputeMcagChannelService mappings) { this.mapper = mapper; this.settings = settings; this.gateways=gateways; this.mcag=mcag; this.mappings=mappings; }
    public ComputeExchangeRateClient(ObjectMapper mapper, ComputeExchangeProviderSettings settings) { this(mapper,settings,null,null,null); }
    public ComputeExchangeProviderSettings.Provider configuration(String name) { return settings.provider(name); }
    public boolean lockAndIsCurrent(ComputeExchangeProviderSettings.Provider provider) { return settings.lockAndUnchanged(provider); }
    public boolean cryptoConfigured() { return mcag!=null&&mcag.isEnabled()&&gateways!=null&&gateways.getIfAvailable()!=null; }
    public List<Quote> fiat() throws Exception { return fiat(configuration("FRANKFURTER")); }
    public List<Quote> fiat(ComputeExchangeProviderSettings.Provider provider) throws Exception {
        return parseFiat(fetch(provider.baseUrl() + "/rates?base=USD", provider));
    }
    public List<Quote> crypto(Map<String,String> assets) throws Exception { return crypto(assets, configuration("CRYPTO")); }
    public List<Quote> crypto(Map<String,String> assets, ComputeExchangeProviderSettings.Provider provider) throws Exception {
        if (assets.isEmpty()) return List.of();
        if (!provider.enabled() || !cryptoConfigured()) throw new IllegalStateException("商户行情未配置");
        Map<String,ComputeMcagProperties.Channel> mapped=new LinkedHashMap<>();
        for(var channel:mappings.listChannels()) if(channel.isActive()&&assets.containsKey(channel.getCurrency())) {
            var previous=mapped.putIfAbsent(channel.getAssetId(),channel);
            if(previous!=null&&(!Objects.equals(previous.getCurrency(),channel.getCurrency())||!Objects.equals(previous.getNetworkId(),channel.getNetworkId())))
                throw new IllegalArgumentException("同一商户资产映射冲突");
        }
        if(mapped.isEmpty()) throw new IllegalArgumentException("加密币未配置商户资产映射");
        List<String> ids = mapped.keySet().stream().sorted().toList();
        List<Quote> result = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += 100) {
            List<String> batch = ids.subList(start, Math.min(start + 100, ids.size()));
            var response=gateways.getObject().request("exchange-rates/query",Map.of("asset_ids",batch));
            Map<String,ComputeMcagProperties.Channel> subset=new LinkedHashMap<>();batch.forEach(id->subset.put(id,mapped.get(id)));
            result.addAll(parseMerchantRates(response,subset,Instant.now()));
        }
        // 同币多网络仅选择最近成功快照；汇率只用于平台估值，不提交兑换或更改支付原币数量。
        Map<String,Quote> latest=new LinkedHashMap<>();
        for(var quote:result)latest.merge(quote.code(),quote,(old,next)->next.sourceAt().isAfter(old.sourceAt())?next:old);
        if(latest.isEmpty())throw new IllegalArgumentException("商户行情没有有效报价");
        return List.copyOf(latest.values());
    }
    private JsonNode fetch(String url, ComputeExchangeProviderSettings.Provider provider) throws Exception {
        if (!provider.enabled()) throw new IllegalStateException("汇率接口未启用");
        URI uri = URI.create(url);
        // 自定义兼容域名须在服务端显式授权，并拒绝解析至本地或内网。
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            byte[] bytes = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isMulticastAddress() || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                throw new IllegalArgumentException("汇率接口地址不可用");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET();
        var pending = http.sendAsync(request.build(), response -> new BoundedBody());
        try {
            HttpResponse<byte[]> response = pending.get(12, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new IllegalStateException("汇率服务暂不可用");
            return mapper.readTree(response.body());
        } finally { if (!pending.isDone()) pending.cancel(true); }
    }
    /** 在接收过程中限制正文大小，不等下载结束才校验。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> values) {
            for (ByteBuffer value : values) {
                if (buffer.size() + value.remaining() > 1024 * 1024) { subscription.cancel(); body.completeExceptionally(new IllegalArgumentException("汇率响应过大")); return; }
                byte[] bytes = new byte[value.remaining()]; value.get(bytes); buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(buffer.toByteArray()); }
    }
    static List<Quote> parseFiat(JsonNode json) {
        if (!json.isArray()) throw new IllegalArgumentException("法币汇率响应格式错误");
        List<Quote> result = new ArrayList<>();
        for (JsonNode item : json) {
            String code = item.path("quote").asText().toUpperCase(Locale.ROOT);
            if (!"USD".equalsIgnoreCase(item.path("base").asText()) || !code.matches("[A-Z]{3}")) continue;
            BigDecimal rate = validRate(item.get("rate"));
            if (rate == null) continue;
            try {
                Instant date = LocalDate.parse(item.path("date").asText()).atStartOfDay(ZoneOffset.UTC).toInstant();
                if (date.isAfter(Instant.now().plusSeconds(86400))) continue;
                result.add(new Quote(code, rate, "FRANKFURTER", code, date));
            } catch (DateTimeException ignored) { /* 错误行不覆盖上一次成功值。 */ }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("法币汇率响应没有有效数据");
        return result;
    }
    static List<Quote> parseMerchantRates(Map<String,Object> response, Map<String,ComputeMcagProperties.Channel> assets, Instant now) {
        if (!"USDT".equals(response.get("quote_currency")) || !(response.get("items") instanceof List<?> items) || items.size()>100)
            throw new IllegalArgumentException("商户行情响应格式错误");
        List<Quote> result = new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(Object raw:items) {
            if(!(raw instanceof Map<?,?> item))throw new IllegalArgumentException("商户行情行格式错误");
            String id=Objects.toString(item.get("asset_id"),"");var asset=assets.get(id);
            if(asset==null||!seen.add(id)||!asset.getCurrency().equals(item.get("symbol"))||!asset.getNetworkId().equals(item.get("network_id")))
                throw new IllegalArgumentException("商户行情资产映射不一致");
            if(!"AVAILABLE".equals(item.get("status")))continue;
            try {
                BigDecimal rate=new BigDecimal(Objects.toString(item.get("usdt_price"),""));
                Instant observed=Instant.parse(Objects.toString(item.get("observed_at"),"")), expires=Instant.parse(Objects.toString(item.get("expires_at"),""));
                String snapshot=Objects.toString(item.get("snapshot_id"),"");
                if(rate.signum()<=0||rate.compareTo(new BigDecimal("1e18"))>=0||snapshot.isBlank()||snapshot.length()>128
                        ||observed.isAfter(now.plusSeconds(30))||observed.isBefore(now.minusSeconds(600))||!expires.isAfter(now)||!expires.isAfter(observed)
                        ||expires.isAfter(observed.plusSeconds(600)))continue;
                rate=rate.setScale(18,java.math.RoundingMode.HALF_UP);if(rate.signum()<=0)continue;
                // 平台已明确 USD=USDT=Token；保留 CRYPTO 来源，不能冒称直接取得市场 USD 报价。
                result.add(new Quote(asset.getCurrency(),rate,"CRYPTO",snapshot,observed));
            } catch(NumberFormatException|DateTimeException ignored) { /* 错误或过期报价保留本地成功缓存。 */ }
        }
        return result;
    }
    private static BigDecimal validRate(JsonNode value) {
        if (value == null || !value.isNumber()) return null;
        BigDecimal rate = value.decimalValue();
        if (rate.signum() <= 0 || rate.compareTo(new BigDecimal("1000000000000000000")) >= 0) return null;
        rate = rate.setScale(18, java.math.RoundingMode.HALF_UP);
        return rate.signum() > 0 ? rate : null;
    }
}
