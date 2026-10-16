package org.token.service.compute;

import org.token.repository.compute.ComputePresentationRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.token.config.EnabledLocaleResolver;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 用户展示配置与只读资产估值；不参与原币钱包、报价、记账或等级升级。 */
@Service
public class ComputePresentationService {
    public static final String SETTING_KEY = "userPresentation";
    private final ComputeData data;
    private final ComputePresentationRepository repository;
    private final EnabledLocaleResolver locales;
    private final ComputeExchangeRateService exchangeRates;
    private final org.token.service.SystemSettingService systemSettings;
    private final ComputeTeamOverviewService teams;

    @Autowired
    public ComputePresentationService(ComputeData data, EnabledLocaleResolver locales, ComputeExchangeRateService exchangeRates,
                                      org.token.service.SystemSettingService systemSettings, ComputePresentationRepository repository,
                                      ComputeTeamOverviewService teams) {
        this.data = data; this.locales = locales; this.exchangeRates = exchangeRates; this.systemSettings = systemSettings;

        this.repository = repository;
        this.teams = teams;
    }

    public ComputePresentationService(ComputeData data, EnabledLocaleResolver locales, ComputeExchangeRateService exchangeRates,
                                      org.token.service.SystemSettingService systemSettings) {
        this(data, locales, exchangeRates, systemSettings, new ComputePresentationRepository(data),
                new ComputeTeamOverviewService(data, new ComputeMemberRelations(data.jdbc()), systemSettings));
    }
    public ComputePresentationService(ComputeData data, EnabledLocaleResolver locales, ComputeExchangeRateService exchangeRates) { this(data, locales, exchangeRates, null); }
    public ComputePresentationService(ComputeData data, EnabledLocaleResolver locales) { this(data, locales, null); }

    public Map<String, Object> settings() {
        Map<String, Object> defaults = defaults();
        defaults.putAll(data.jsonMap(data.setting(SETTING_KEY, "{}")));
        defaults.put("autoDetectLanguage", enabled(defaults.get("autoDetectLanguage")));
        defaults.put("unitName", displayUnitName(defaults.get("unitName")));
        defaults.put("displayGift", displayGift(defaults.get("displayGift")));
        Map<String, Object> runtime = ComputeRuntimeProjection.complete(map(defaults.get("runtime")));
        runtime.put("enabled", enabled(runtime.get("enabled")));
        defaults.put("runtime", runtime);
        // 语言可能在其他管理页面被停用，公开配置即时落回有效语言。
        defaults.put("defaultLanguage", locales.resolve(text(defaults.get("defaultLanguage"))));
        if (exchangeRates != null) defaults.put("valuation", exchangeRates.valuation(map(defaults.get("valuation"))));
        else {
            Map<String,Object> valuation = map(defaults.get("valuation"));
            Map<String,Object> prices = map(valuation.get("assetUsdRates"));
            for (String stable : List.of("USD", "USDT", "USDC", "TOKEN")) prices.put(stable,"1");
            valuation.put("assetUsdRates", prices); defaults.put("valuation", valuation);
        }
        return defaults;
    }

    public Map<String,Object> adminSettings() {
        Map<String,Object> result = settings();
        if (exchangeRates != null) result.put("exchangeStatus", exchangeRates.status());
        return result;
    }

    public Map<String, Object> publicSettings() {
        Map<String, Object> result = settings();
        // 赠送金额仅随已认证会员的展示总额返回，不在游客配置中展示。
        result.remove("displayGift");
        Map<String, Object> runtime = ComputeRuntimeProjection.project(map(result.get("runtime")), java.time.Instant.now());
        result.put("runtime", runtime);
        Map<String, Object> valuation = map(result.get("valuation"));
        valuation.put("unit", "token"); valuation.put("usdPerToken", "1");
        result.put("valuation", valuation);
        return result;
    }

    /** 首页轻量读取；不查询行情、语言或正文，也不触发任何外部请求。 */
    public Map<String,Object> runtime() {
        Map<String,Object> config = data.jsonMap(data.setting(SETTING_KEY, "{}"));
        return ComputeRuntimeProjection.project(config.get("runtime") instanceof Map<?,?> value ? map(value) : Map.of(), java.time.Instant.now());
    }

    @Transactional
    public Map<String, Object> save(Map<String, Object> request) {
        request = new LinkedHashMap<>(request);
        Map<String,Object> previous = defaults();
        previous.putAll(data.jsonMap(data.setting(SETTING_KEY, "{}")));
        // 兼容尚未携带新增字段的管理端，保存其他设置时不能重置已配置赠送金额。
        request.putIfAbsent("displayGift", previous.get("displayGift"));
        request.putIfAbsent("unitName", previous.get("unitName"));
        if (exchangeRates != null) {
            request.put("valuation", previous.get("valuation"));
        }
        Map<String, Object> result = validate(request);
        repository.saveSettingWrite(SETTING_KEY, data.json(result), now());
        return settings();
    }

    Map<String, Object> validate(Map<String, Object> request) {
        String language = locales.normalize(text(request.get("defaultLanguage")));
        if (language.isBlank() || locales.enabledLanguages().stream().noneMatch(option -> option.value().equals(language)))
            throw invalid();
        Map<String, Object> result = fields("defaultLanguage", language, "autoDetectLanguage", enabled(request.get("autoDetectLanguage")));
        result.put("unitName", displayUnitName(request.get("unitName")));
        result.put("displayGift", displayGift(request.get("displayGift")));
        if (!(request.get("slides") instanceof List<?> slides) || slides.size() > 10) throw invalid();
        List<Map<String, Object>> safeSlides = new ArrayList<>();
        for (Object raw : slides) {
            Map<String, Object> slide = map(raw);
            String image = text(slide.get("imageUrl")), target = text(slide.get("targetPath"));
            if (image.isBlank() || image.length() > 2000 || !imageUrl(image)) throw invalid();
            // 跳转仅接受用户端路由，不接受外部重定向、协议相对地址或脚本。
            if (!target.isEmpty() && !target.matches("/[A-Za-z0-9/_-]*")) throw invalid();
            safeSlides.add(fields("imageUrl", image, "targetPath", target));
        }
        result.put("slides", safeSlides);
        Map<String, Object> input = map(request.get("runtime"));
        Map<String, Object> runtime = fields("enabled", enabled(input.get("enabled")));
        ComputeRuntimeProjection.validate(runtime, input);
        boolean automatic = "AUTO".equals(runtime.get("mode"));
        for (String key : List.of("totalNodes", "activeNodes", "totalPools", "runningPools")) {
            BigDecimal value = automatic && Set.of("activeNodes", "runningPools").contains(key) ? BigDecimal.ZERO : bounded(input.get(key));
            if (value.stripTrailingZeros().scale() > 0) throw invalid();
            runtime.put(key, value.longValueExact());
        }
        for (String key : List.of("capacity", "consumed")) runtime.put(key, automatic && "consumed".equals(key) ? "0" : bounded(input.get(key)).toPlainString());
        if (automatic) {
            Map<String,Object> projectionInput = new LinkedHashMap<>(runtime);
            projectionInput.put("enabled", true);
            Map<String,Object> projection = ComputeRuntimeProjection.project(projectionInput, java.time.Instant.now());
            for (String key : List.of("activeNodes", "runningPools", "consumed")) runtime.put(key, projection.get(key));
        }
        if (number(runtime.get("activeNodes")) > number(runtime.get("totalNodes"))
                || number(runtime.get("runningPools")) > number(runtime.get("totalPools"))
                || decimal(runtime.get("consumed")).compareTo(decimal(runtime.get("capacity"))) > 0
                || (number(runtime.get("activeNodes")) == 0 && (decimal(runtime.get("consumed")).signum() > 0 || number(runtime.get("runningPools")) > 0)))
            throw new BusinessException("server.compute.ui.runtimeInvalid");
        result.put("runtime", runtime);
        Map<String, Object> valuation = map(request.get("valuation"));
        Map<String, String> asset = rates(valuation.get("assetUsdRates"), false);
        Map<String, String> fiat = rates(valuation.get("fiatPerUsd"), true);
        if (fiat.containsKey("USD") && decimal(fiat.get("USD")).compareTo(BigDecimal.ONE) != 0) throw invalid();
        fiat.put("USD", "1");
        result.put("valuation", fields("assetUsdRates", asset, "fiatPerUsd", fiat));
        return result;
    }

    private Map<String, String> rates(Object raw, boolean fiat) {
        Map<String, Object> input = map(raw);
        if (input.size() > 100) throw invalid();
        Set<String> assets = fiat ? Set.of() : new HashSet<>(repository.ratesCurrencyRows());
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            String code = entry.getKey().toUpperCase(Locale.ROOT);
            if (!code.equals(entry.getKey()) || !code.matches(fiat ? "[A-Z]{3}" : "[A-Z0-9]{2,16}") || (!fiat && !assets.contains(code))) throw invalid();
            if (fiat) try { Currency.getInstance(code); } catch (IllegalArgumentException exception) { throw invalid(); }
            BigDecimal value = bounded(entry.getValue());
            if (value.signum() == 0) throw invalid();
            result.put(code, value.toPlainString());
        }
        return result;
    }

    /** 只有非零可用余额需要估值；冻结金额保留展示但不计入总资产。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> member(long memberId) {
        Map<String, Object> member = repository.memberMemberRow(memberId);
        Map<String, Object> config = settings();
        Map<String, Object> prices = map(map(config.get("valuation")).get("assetUsdRates"));
        List<Map<String, Object>> wallets = repository.memberWalletRows(memberId);
        Set<String> missing = new LinkedHashSet<>();
        BigDecimal total = BigDecimal.ZERO, available = BigDecimal.ZERO, participating = BigDecimal.ZERO;
        for (var wallet : wallets) {
            BigDecimal balance = decimal(wallet.get("available"));
            String currency = text(wallet.get("currency"));
            Object price = prices.get(currency);
            if (price == null && balance.signum() != 0) { missing.add(currency); wallet.put("token", null); continue; }
            BigDecimal value = price == null ? BigDecimal.ZERO : balance.multiply(decimal(price)).setScale(8, RoundingMode.HALF_UP);
            wallet.put("token", value.toPlainString()); total = total.add(value);
            if ("EARNINGS".equals(wallet.get("type"))) available = available.add(value);
            if ("COMPUTE".equals(wallet.get("type"))) participating = participating.add(value);
        }
        Map<String, Object> assets = fields("unit", "token", "totalToken", total.toPlainString(), "complete", missing.isEmpty(),
                "unpricedCurrencies", missing, "wallets", wallets, "availableToken", available.toPlainString(), "participatingToken", participating.toPlainString());
        Map<String,Object> gift = map(config.get("displayGift"));
        BigDecimal giftToken = enabled(gift.get("enabled")) ? decimal(gift.get("amountToken")) : BigDecimal.ZERO;
        // 独立展示投影，不改真实 assets、钱包、收益、推荐或等级计算的输入。
        Map<String,Object> displayAssets = fields("totalToken", total.add(giftToken).toPlainString(), "giftToken", giftToken.toPlainString());
        String currency = ComputeUsdAccounting.WALLET_CURRENCY;
        var eligibility = new ComputeVipEligibility(data);
        BigDecimal availableBalance = eligibility.availableComputeBalance(memberId);
        List<Map<String, Object>> next = repository.memberLevelRows(member.get("level"));
        // invested 保留为旧客户端字段别名，数值与新口径一致，避免部署切换期间展示旧充值额。
        Map<String, Object> progress = fields("currentLevel", member.get("level"), "nextLevel", next.isEmpty() ? null : next.get(0).get("id"), "invested", availableBalance.toPlainString(), "availableBalance", availableBalance.toPlainString(), "currency", currency);
        BigDecimal threshold = next.isEmpty() ? BigDecimal.ZERO : decimal(next.get(0).get("threshold"));
        progress.put("threshold", threshold.toPlainString()); progress.put("remaining", threshold.subtract(availableBalance).max(BigDecimal.ZERO).toPlainString());
        progress.put("percent", next.isEmpty() || threshold.signum() == 0 ? "100" : availableBalance.multiply(BigDecimal.valueOf(100)).divide(threshold, 2, RoundingMode.HALF_UP).min(BigDecimal.valueOf(100)).toPlainString());
        List<Map<String, Object>> levelProgress = eligibility.levels(memberId);
        progress.put("levels", levelProgress);
        Map<String, Object> nextProgress = next.isEmpty() ? Map.of() : levelProgress.stream()
                .filter(row -> number(row.get("levelId")) == number(progress.get("nextLevel"))).findFirst().orElse(Map.of());
        for (String field : List.of("requiredDirectMembers", "qualifiedDirectMembers", "remainingDirectMembers"))
            progress.put(field, number(nextProgress.getOrDefault(field, 0)));
        progress.put("directMemberDepositThreshold", decimal(nextProgress.get("directMemberDepositThreshold")).toPlainString());
        progress.put("directMemberRequirementMet", nextProgress.isEmpty() || enabled(nextProgress.get("directMemberRequirementMet")));
        long requiredDirectMembers = number(progress.get("requiredDirectMembers"));
        if (requiredDirectMembers > 0) {
            BigDecimal memberPercent = BigDecimal.valueOf(number(progress.get("qualifiedDirectMembers"))).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(requiredDirectMembers), 2, RoundingMode.HALF_UP).min(BigDecimal.valueOf(100));
            progress.put("percent", decimal(progress.get("percent")).min(memberPercent).toPlainString());
        }
        Map<String, Object> auto = repository.memberAutoContractCount(memberId, currency);
        auto.put("currency", currency);
        java.time.ZoneId businessZone = systemSettings == null ? java.time.ZoneOffset.UTC : java.time.ZoneId.of(systemSettings.resolveSystemTimeZone());
        java.time.LocalDate today = now().toInstant(java.time.ZoneOffset.UTC).atZone(businessZone).toLocalDate();
        java.time.LocalDateTime dayStart = java.time.LocalDateTime.ofInstant(today.atStartOfDay(businessZone).toInstant(), java.time.ZoneOffset.UTC);
        java.time.LocalDateTime dayEnd = java.time.LocalDateTime.ofInstant(today.plusDays(1).atStartOfDay(businessZone).toInstant(), java.time.ZoneOffset.UTC);
        auto.put("todayIncome", decimal(repository.memberLedgerValue(memberId, currency, dayStart, dayEnd)).toPlainString());
        Map<String, Object> invitation = teams.invitation(memberId);
        return fields("username", member.get("username"), "securityScore", member.get("securityScore"), "valuation", config.get("valuation"), "assets", assets, "vipProgress", progress, "autoSummary", auto,
                "invitation", invitation, "displayAssets", displayAssets,
                "unread", unread(memberId));
    }

    public Map<String, Object> unread(long memberId) {
        Map<String, Object> counts = repository.unreadContentRecipientCount(memberId, now(), now());
        counts.put("tasks",0); counts.put("notifications", 0); counts.put("total", number(counts.get("messages")));
        return counts;
    }

    public static Map<String, Object> defaults() {
        return fields("defaultLanguage", "en", "autoDetectLanguage", false, "unitName", "token", "slides", List.of(),
                "displayGift", fields("enabled", false, "amountToken", "0"),
                "runtime", ComputeRuntimeProjection.defaults(),
                "valuation", fields("assetUsdRates", Map.of(), "fiatPerUsd", Map.of("USD", "1")));
    }

    private static boolean imageUrl(String value) {
        if (value.startsWith("/") && !value.startsWith("//") && !value.contains("\\") && !value.contains("\n") && !value.contains("\r")) return true;
        try { URI uri = URI.create(value); return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null; }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static BigDecimal bounded(Object value) {
        BigDecimal number = decimal(value);
        if (number.signum() < 0 || number.scale() > 8 || number.compareTo(new BigDecimal("1000000000000")) > 0) throw invalid();
        return number;
    }
    private static Map<String,Object> displayGift(Object value) {
        Map<String,Object> input = value == null ? Map.of() : map(value);
        return fields("enabled", enabled(input.get("enabled")), "amountToken", bounded(input.get("amountToken")).toPlainString());
    }
    private static String displayUnitName(Object value) {
        if (value != null && !(value instanceof String)) throw invalid();
        String name = value == null ? "" : value.toString().strip();
        if (name.isEmpty()) return "token";
        if (name.codePointCount(0, name.length()) > 24
                || name.codePoints().anyMatch(code -> Character.isISOControl(code)
                || Character.getType(code) == Character.FORMAT)
                || name.indexOf('<') >= 0 || name.indexOf('>') >= 0) throw invalid();
        return name;
    }
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> input)) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>(); input.forEach((key, item) -> result.put(text(key), item)); return result;
    }
    private static BusinessException invalid() { return new BusinessException("server.compute.ui.settingsInvalid"); }
}
