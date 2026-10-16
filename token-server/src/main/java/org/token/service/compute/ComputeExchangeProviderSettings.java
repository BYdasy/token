package org.token.service.compute;

import org.token.repository.compute.ComputeExchangeProviderSettingsRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 法币连接与商户行情启停；历史加密币密钥配置保持封存，不读取、不下发、不再调用。 */
@Service
public class ComputeExchangeProviderSettings {
    static final String KEY = "exchangeProviders";
    static final String FIAT_URL = "https://api.frankfurter.dev/v2";
    static final Duration FIAT_REFRESH_INTERVAL = Duration.ofHours(12);
    static final Duration CRYPTO_REFRESH_INTERVAL = Duration.ofMinutes(30);
    private final ComputeData data;
    private final ComputeExchangeProviderSettingsRepository repository;
    private final Set<String> allowedHosts;

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeExchangeProviderSettings(ComputeData data,
            @Value("${EXP_RATE_ALLOWED_HOSTS:}") String additionalHosts, ComputeExchangeProviderSettingsRepository repository) {
        this.data = data;
        Set<String> hosts = new LinkedHashSet<>(List.of("api.frankfurter.dev"));
        for (String host : additionalHosts.split(",")) if (!host.isBlank()) hosts.add(host.trim().toLowerCase(Locale.ROOT));
        this.allowedHosts = Collections.unmodifiableSet(hosts);

        this.repository = repository;
    }

    public ComputeExchangeProviderSettings(ComputeData data,
            @Value("${EXP_RATE_ALLOWED_HOSTS:}") String additionalHosts) {
        this(data, additionalHosts, new ComputeExchangeProviderSettingsRepository(data));
    }

    public record Provider(String name, boolean enabled, String baseUrl, String plan, String apiKey, long revision) {
        @Override public String toString() { return name + "[enabled=" + enabled + ", revision=" + revision + "]"; }
    }

    /** 自动采集频率由供应商数据性质决定，不读取历史 JSON 中的短周期扩展字段。 */
    static Duration refreshInterval(String provider) {
        return switch(provider) {
            case "FRANKFURTER" -> FIAT_REFRESH_INTERVAL;
            case "CRYPTO" -> CRYPTO_REFRESH_INTERVAL;
            default -> throw invalid();
        };
    }

    static Map<String,Long> refreshMinutesByProvider() {
        return Map.of("FRANKFURTER",FIAT_REFRESH_INTERVAL.toMinutes(),"CRYPTO",CRYPTO_REFRESH_INTERVAL.toMinutes());
    }

    private Map<String,Object> stored() { return data.jsonMap(data.setting(KEY, "{}")); }

    private Map<String,Object> lockConfiguration() {
        // 保持与现有业务设置写入相同的锁顺序，并使用锁定读取得最新已提交值。
        var locked = repository.lockConfigurationSettingRows();
        return locked.stream().filter(value -> KEY.equals(value.get("setting_key")))
                .findFirst().map(value -> data.jsonMap(text(value.get("setting_value")))).orElseGet(LinkedHashMap::new);
    }

    private Map<String,Object> row(Map<String,Object> config, String provider) {
        if (!Set.of("FRANKFURTER", "CRYPTO").contains(provider)) throw invalid();
        Map<String,Object> row = new LinkedHashMap<>(fields("enabled", true, "baseUrl", "FRANKFURTER".equals(provider) ? FIAT_URL : "", "revision", 0));
        if (config.get(provider) instanceof Map<?,?> values) values.forEach((key,value) -> row.put(text(key), value));
        return row;
    }

    public Provider provider(String name) {
        Map<String,Object> row = row(stored(), name);
        return new Provider(name, enabled(row.get("enabled")), "FRANKFURTER".equals(name)?validatedUrl(text(row.get("baseUrl"))):"", "", "", number(row.get("revision")));
    }

    public Map<String,Object> publicSettings() {
        Map<String,Object> config = stored();
        List<Map<String,Object>> rows = new ArrayList<>();
        for (String name : List.of("FRANKFURTER", "CRYPTO")) {
            Map<String,Object> row = row(config, name);
            rows.add(fields("provider", name, "enabled", enabled(row.get("enabled")), "baseUrl", "FRANKFURTER".equals(name)?row.get("baseUrl"):"",
                    "managedExternally", "CRYPTO".equals(name), "revision", row.get("revision"),
                    "refreshMinutes", refreshInterval(name).toMinutes()));
        }
        return fields("providers", rows, "refreshMinutes", CRYPTO_REFRESH_INTERVAL.toMinutes(),
                "refreshMinutesByProvider", refreshMinutesByProvider(), "allowedHosts", allowedHosts);
    }

    @Transactional
    public Map<String,Object> save(String name, Map<String,Object> input) {
        // 与其他设置写入采用相同配置锁，防止两个管理员同时覆盖不同供应商。
        Map<String,Object> config = lockConfiguration();
        Map<String,Object> old = row(config, name);
        long revision = number(old.get("revision"));
        if (number(input.getOrDefault("revision", -1)) != revision) throw new BusinessException("server.compute.rates.settingsChanged");
        String url = "FRANKFURTER".equals(name)?validatedUrl(text(input.get("baseUrl"))):"";
        config.put(name, fields("enabled", enabled(input.get("enabled")), "baseUrl", url, "revision", revision + 1));
        repository.saveSettingWrite(KEY, data.json(config), now());
        return publicSettings();
    }

    /** 调用方必须在外部请求完成后开启短事务，持锁直到报价与刷新状态提交。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean lockAndUnchanged(Provider value) {
        return number(row(lockConfiguration(), value.name()).get("revision")) == value.revision();
    }

    String validatedUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() != -1 && uri.getPort() != 443
                    || value.length() > 500 || value.contains("\\") || uri.getPath().contains("..")) throw invalid();
            return value.replaceAll("/+$", "");
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private static BusinessException invalid() { return new BusinessException("server.compute.rates.invalidSettings"); }
}
