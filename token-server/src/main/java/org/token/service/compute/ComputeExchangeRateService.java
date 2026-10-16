package org.token.service.compute;

import org.token.repository.compute.ComputeExchangeRateRepository;

import lombok.extern.slf4j.Slf4j;
import org.token.repository.compute.ComputeData;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 法币每十二小时、加密币每三十分钟采集；读请求只用持久化成功值，不等待行情供应商。 */
@Slf4j
@Service
public class ComputeExchangeRateService {
    private final ComputeData data;
    private final ComputeExchangeRateRepository repository;
    private final ComputeExchangeRateClient client;
    private final java.util.concurrent.Executor executor;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile List<Map<String,Object>> lastRows = List.of();

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeExchangeRateService(ComputeData data, ComputeExchangeRateClient client,
            @org.springframework.beans.factory.annotation.Qualifier("computeRateExecutor") java.util.concurrent.Executor executor,
            org.springframework.transaction.PlatformTransactionManager transactionManager, ComputeExchangeRateRepository repository) {
        this(data,client,executor,transactionManager,repository,Clock.systemUTC());
    }

    ComputeExchangeRateService(ComputeData data,ComputeExchangeRateClient client,java.util.concurrent.Executor executor,
            org.springframework.transaction.PlatformTransactionManager transactionManager,ComputeExchangeRateRepository repository,Clock clock) {
        this.data=data; this.client=client; this.executor=executor; this.transactionManager=transactionManager;
        this.repository = repository;
        this.clock=clock;
    }

    public ComputeExchangeRateService(ComputeData data, ComputeExchangeRateClient client,
            @org.springframework.beans.factory.annotation.Qualifier("computeRateExecutor") java.util.concurrent.Executor executor,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this(data, client, executor, transactionManager, new ComputeExchangeRateRepository(data));
    }
    public ComputeExchangeRateService(ComputeData data, ComputeExchangeRateClient client, java.util.concurrent.Executor executor) { this(data,client,executor,null); }
    public ComputeExchangeRateService(ComputeData data, ComputeExchangeRateClient client) { this(data, client, Runnable::run); }

    @Scheduled(initialDelay = 10_000, fixedDelay = 1_800_000)
    public void scheduleRefresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        try { executor.execute(() -> { try { refresh(); } finally { refreshing.set(false); } }); }
        catch (RuntimeException rejected) { refreshing.set(false); }
    }
    public void refresh() {
        refresh(List.of("FRANKFURTER", "CRYPTO"), false);
    }
    /** 手动请求快速返回，执行状态复用持久化刷新记录，不占用 HTTP 等待外部服务。 */
    public Map<String,Object> requestRefresh(String provider) {
        if (!List.of("FRANKFURTER", "CRYPTO").contains(provider))
            throw new org.token.exception.BusinessException("server.compute.rates.invalidSettings");
        if (!refreshing.compareAndSet(false, true)) return fields("accepted", false, "status", "RUNNING");
        LocalDateTime attempt=currentTime();
        try {
            // 先取得持久租约再返回已接受，首次状态查询也能区分本次与上一次结果。
            if (!claim(provider,true,attempt)) { refreshing.set(false); return fields("accepted",false,"status","RUNNING"); }
            executor.execute(() -> { try { refreshProvider(provider,attempt); } finally { refreshing.set(false); } });
        } catch (RuntimeException rejected) {
            refreshing.set(false);
            failed(provider,attempt);
            throw new org.token.exception.BusinessException("server.compute.rates.busy");
        }
        return fields("accepted", true, "status", "RUNNING", "attemptAt",attempt.toInstant(ZoneOffset.UTC).toString());
    }
    private void refresh(List<String> providers, boolean force) {
        for (String provider : providers) {
            LocalDateTime attempt=currentTime();
            try {
                if(claim(provider,force,attempt)) refreshProvider(provider,attempt);
            } catch (Exception exception) {
                failed(provider,attempt);
            }
        }
    }
    private boolean claim(String provider,boolean force,LocalDateTime attempt) {
        Duration interval=ComputeExchangeProviderSettings.refreshInterval(provider);
        return repository.claimProvider(provider, force, attempt, attempt.plus(interval), attempt.minusSeconds(30),
                attempt.minus(interval), attempt.minusMinutes(30)) == 1;
    }
    private LocalDateTime currentTime() { return LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private void refreshProvider(String provider,LocalDateTime attempt) {
        try {
            var configuration=client.configuration(provider);
            if(configuration!=null&&(!configuration.enabled()||"CRYPTO".equals(provider)&&!client.cryptoConfigured())) {
                finish(provider,attempt,configuration.enabled()?"NOT_CONFIGURED":"DISABLED");return;
            }
            List<ComputeExchangeRateClient.Quote> quotes=configuration==null
                    ? ("FRANKFURTER".equals(provider)?client.fiat():client.crypto(enabledCrypto()))
                    : ("FRANKFURTER".equals(provider)?client.fiat(configuration):client.crypto(enabledCrypto(),configuration));
            // 外部网络已结束，以下锁只覆盖配置版本校验与本地成功缓存提交。
            var manager=transactionManager!=null?transactionManager:new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(data.jdbc().getDataSource()));
            var transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
            transaction.executeWithoutResult(status->{
                boolean same=configuration==null||client.lockAndIsCurrent(configuration);
                // 已失去租约的旧执行不能修改新一轮刷新记录和报价。
                if(repository.refreshProviderExchangeRefreshRows(provider, attempt).isEmpty())return;
                if(!same) {
                    repository.refreshProviderExchangeRefreshWrite(currentTime(), provider);
                    return;
                }
                for(var quote:quotes)persist("FRANKFURTER".equals(provider)?"FIAT":"ASSET",quote,attempt);
                repository.refreshProviderExchangeRefreshWrite2(quotes.isEmpty()?"NO_ASSETS":"SUCCEEDED", !quotes.isEmpty(), currentTime(), provider);
            });
        } catch(Exception exception) { failed(provider,attempt); }
    }
    private void finish(String provider,LocalDateTime attempt,String status) {
        repository.finishExchangeRefreshWrite(status, provider, attempt);
    }
    private void failed(String provider,LocalDateTime attempt) {
        // 不记录异常消息、请求 URL、请求头或供应商正文，避免任何密钥回显。
        log.warn("{} 汇率更新失败，继续使用上一次成功值",provider);
        try { finish(provider,attempt,"FAILED"); }
        catch(Exception ignored) { /* 数据库不可用时保留成功缓存，租约到期后恢复。 */ }
    }
    Map<String,String> enabledCrypto() {
        Map<String,String> result = new LinkedHashMap<>();
        for (var row : repository.enabledCryptoCurrencyAssetRows()) {
            String code = text(row.get("assetCode"));
            if (!Set.of("USD", "USDT", "USDC", "USDT0", "TOKEN").contains(code)) result.put(code, code);
        }
        return result;
    }
    void persist(String type, ComputeExchangeRateClient.Quote quote, LocalDateTime refreshedAt) {
        if (Set.of("USD", "USDT", "USDC", "USDT0", "TOKEN").contains(quote.code())) return;
        LocalDateTime sourceAt = LocalDateTime.ofInstant(quote.sourceAt(), ZoneOffset.UTC);
        repository.persistExchangeRateWrite(type, quote.code(), quote.rate(), quote.source(), quote.sourceId(), sourceAt, refreshedAt);
    }
    public Map<String,Object> valuation(Map<String,Object> legacy) {
        Map<String,Object> assets = new LinkedHashMap<>(), fiats = new LinkedHashMap<>();
        // 旧人工估值只作为首次上线的兼容缓存，不再由新设置表单改写。
        if (legacy.get("assetUsdRates") instanceof Map<?,?> existing) existing.forEach((k,v) -> assets.put(text(k),v));
        if (legacy.get("fiatPerUsd") instanceof Map<?,?> existing) existing.forEach((k,v) -> fiats.put(text(k),v));
        List<Map<String,Object>> rows;
        try { rows = repository.valuationExchangeRateRows(); lastRows = rows; }
        catch (Exception exception) { rows = lastRows; }
        List<Map<String,Object>> sources = new ArrayList<>();
        Instant current = clock.instant();
        for (var row : rows) {
            String code = text(row.get("currency"));
            ("FIAT".equals(row.get("rateType")) ? fiats : assets).put(code, row.get("rate"));
            Map<String,Object> source = new LinkedHashMap<>(row);
            Duration refreshInterval="FRANKFURTER".equals(row.get("provider"))
                    ? ComputeExchangeProviderSettings.FIAT_REFRESH_INTERVAL : ComputeExchangeProviderSettings.CRYPTO_REFRESH_INTERVAL;
            source.put("stale", Instant.parse(text(row.get("refreshedAt"))).plus(refreshInterval).isBefore(current)
                    || (Set.of("COINGECKO","CRYPTO").contains(row.get("provider")) && Instant.parse(text(row.get("sourceAt"))).plusSeconds(1800).isBefore(current)));
            sources.add(source);
        }
        // 法币钱包也可以估值，但只有明确标为FIAT的资产才使用法币行情倒数，避免同名加密币串价。
        List<Map<String,Object>> fiatAssets;
        try { fiatAssets = repository.valuationCurrencyAssetRows(); }
        catch (Exception exception) { fiatAssets = List.of(); }
        for (var asset : fiatAssets) {
            String code = text(asset.get("assetCode"));
            var reference = sources.stream().filter(row -> "FIAT".equals(row.get("rateType")) && code.equals(row.get("currency"))).findFirst();
            if (reference.isEmpty() || decimal(reference.get().get("rate")).signum() <= 0) continue;
            String usdPerFiat = BigDecimal.ONE.divide(decimal(reference.get().get("rate")), 18, java.math.RoundingMode.HALF_UP).toPlainString();
            assets.put(code, usdPerFiat);
            Map<String,Object> derived = new LinkedHashMap<>(reference.get());
            derived.put("rateType", "ASSET"); derived.put("rate", usdPerFiat); sources.add(derived);
        }
        for (String code : List.of("USD", "USDT", "USDC", "USDT0", "TOKEN")) assets.put(code,"1");
        fiats.put("USD","1");
        return fields("unit", "token", "usdPerToken", "1", "assetUsdRates", assets, "fiatPerUsd", fiats, "sources", sources,
                "refreshMinutes",ComputeExchangeProviderSettings.CRYPTO_REFRESH_INTERVAL.toMinutes(),
                "refreshMinutesByProvider",ComputeExchangeProviderSettings.refreshMinutesByProvider());
    }
    public Map<String,Object> status() {
        return fields("cryptoConfigured", client.cryptoConfigured(), "providers", repository.statusExchangeRefreshRows());
    }

    /** 取得当前成功行情供资金事务留存快照；仅本地读取，绝不等待外部行情。 */
    public Map<String,Object> currentUsdQuote(String currency) {
        String code = text(currency).toUpperCase(Locale.ROOT);
        if (Set.of("USD", "USDT", "USDC", "USDT0", "TOKEN").contains(code)) {
            String at = Instant.now().toString();
            return fields("usdRate", BigDecimal.ONE, "provider", "FIXED", "sourceAt", at, "refreshedAt", at);
        }
        Map<String,Object> legacy = Map.of();
        try {
            Object value = data.jsonMap(data.setting("userPresentation", "{}")).get("valuation");
            if (value instanceof Map<?,?> raw) {
                legacy = new LinkedHashMap<>();
                for (var entry : raw.entrySet()) legacy.put(text(entry.getKey()), entry.getValue());
            }
        } catch (RuntimeException ignored) { /* 不完整的旧展示配置不能阻断已确认的收付款。 */ }
        Map<String,Object> prices = valuation(legacy);
        if (!(prices.get("assetUsdRates") instanceof Map<?,?> assets) || !assets.containsKey(code)) return Map.of();
        BigDecimal rate = decimal(assets.get(code));
        if (rate.signum() <= 0) return Map.of();
        if (prices.get("sources") instanceof List<?> sources) for (int index = sources.size() - 1; index >= 0; index--) {
            if (sources.get(index) instanceof Map<?,?> source && "ASSET".equals(source.get("rateType")) && code.equals(source.get("currency")))
                return fields("usdRate", rate, "provider", source.get("provider"), "sourceAt", source.get("sourceAt"), "refreshedAt", source.get("refreshedAt"));
        }
        return fields("usdRate", rate, "provider", "LEGACY_CONFIG", "sourceAt", null, "refreshedAt", null);
    }
}
