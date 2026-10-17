package org.token.service.compute;

import org.token.repository.compute.ComputeCatalogRepository;

import org.token.repository.compute.ComputeData;
import org.token.config.EnabledLocaleResolver;
import org.token.service.compute.ComputeBusinessTranslations;
import org.token.service.compute.ComputeContentHtml;
import org.token.service.compute.ComputeMoneyPolicy;
import org.token.service.compute.ComputeLoginMethodOrder;

import org.springframework.beans.factory.annotation.Autowired;
import org.token.service.compute.ComputeCredentials;
import org.token.exception.BusinessException;
import org.token.util.PasswordUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 配置入口：白名单字段、区间检查与关联校验在保存前完成。 */
@Service
public class ComputeCatalogService {
    private final ComputeData data;
    private final ComputeCatalogRepository repository;
    private final PasswordUtils passwords;
    private final ComputeCurrencyService currencies;
    private final ComputeContentTranslationService translation;
    private final EnabledLocaleResolver locales;
    private final ComputeMemberUidAllocator memberUids;
    @Autowired
    public ComputeCatalogService(ComputeData data,PasswordUtils passwords,ComputeCurrencyService currencies,ComputeContentTranslationService translation,EnabledLocaleResolver locales,ComputeMemberUidAllocator memberUids, ComputeCatalogRepository repository) {
        this.data=data;this.passwords=passwords;this.currencies=currencies;this.translation=translation;this.locales=locales;this.memberUids=memberUids;

        this.repository = repository;
    }

    public ComputeCatalogService(ComputeData data,PasswordUtils passwords,ComputeCurrencyService currencies,ComputeContentTranslationService translation,EnabledLocaleResolver locales,ComputeMemberUidAllocator memberUids) {
        this(data, passwords, currencies, translation, locales, memberUids, new ComputeCatalogRepository(data));
    }
    public ComputeCatalogService(ComputeData data,PasswordUtils passwords,ComputeCurrencyService currencies,ComputeContentTranslationService translation,EnabledLocaleResolver locales) {this(data,passwords,currencies,translation,locales,new ComputeMemberUidAllocator(data.jdbc()));}
    public ComputeCatalogService(ComputeData data,PasswordUtils passwords,ComputeCurrencyService currencies){this(data,passwords,currencies,null,null);}
    public ComputeCatalogService(ComputeData data, PasswordUtils passwords) {
        this(data, passwords, new ComputeCurrencyService(data));
    }
    private static final Map<String, String> TABLES = Map.of("products", "compute_product", "models", "compute_model",
            "levels", "compute_level", "providers", "compute_provider", "fee-rules", "compute_fee_rule", "transfer-rules", "compute_transfer_rule");
    private static final Set<String> BOOLEAN_SETTINGS = Set.of("guestEnabled", "forgotPasswordEnabled", "usernameEnabled", "usernameRequired",
            "emailEnabled", "emailRequired", "phoneEnabled", "phoneRequired", "withdrawalEnabled", "withdrawalAddressUnique", "withdrawalAddressDeleteEnabled", "inviteCodeRequired", ComputeMemberUidAllocator.USERNAME_LIMIT);

    public Map<String, Object> catalog() {
        return catalog("en");
    }

    public boolean gatewayEnabled() { return currencies.gatewayEnabled(); }

    public Map<String,Object> catalog(String language) {
        List<Map<String,Object>> levels = localizedRows("levels",repository.catalogLevelRows(),language);
        new ComputeLevelPresentation(data).apply(levels);
        return Map.of("summaryCurrency", data.setting("summaryCurrency", "USDT"),
                "products", localizedRows("products",repository.catalogProductRows(),language),
                "models", publicModels(500),
                "levels", levels);
    }

    /** 首页在数据库层限制摘要数量，避免游客读取完整目录或触发无关 VIP 补译。 */
    public Map<String,Object> homeCatalog(String language) {
        return Map.of("summaryCurrency", data.setting("summaryCurrency", "USDT"),
                "products", List.of(), "models", List.of(),
                "partners", repository.homePartners());
    }

    private List<Map<String,Object>> publicModels(int limit) {
        return repository.publicModelsModelRows(limit);
    }

    private List<Map<String,Object>> localizedRows(String resource,List<Map<String,Object>> rows,String language){
        if(translation!=null)rows.forEach(row->translation.applyCatalogTranslation(resource,row,language));
        return rows;
    }

    private Map<String,Object> adminRecord(String resource,Map<String,Object> row){
        if ("fee-rules".equals(resource)) {
            row.put("openWeekdays", ComputeMoneyPolicy.openWeekdays(row.get("openWeekdays")));
        }
        if ("levels".equals(resource)) {
            // JDBC驱动可能将TINYINT(1)读成数字；配置API统一回显布尔值，完整回填后保存仍满足参数校验。
            row.put("autoUpgradeEnabled", enabled(row.get("autoUpgradeEnabled")));
            row.put("autoCooldownHours", decimal(row.get("autoCooldownSeconds")).divide(BigDecimal.valueOf(3600), 8, java.math.RoundingMode.HALF_UP));
            row.put("autoProviderIds", ComputeVipModelAllocation.providerIds(data, row));
        }
        if ("levels".equals(resource)) row.put("rewardRules",ComputeRewardPolicy.readVipRules(data,row.remove("rewardRulesJson")));
        if(translation!=null&&Set.of("products","levels").contains(resource)){
            Map<String,Object> all=translation.translationMap(row.remove("translationsJson"));
            Map<String,Object> source=new LinkedHashMap<>(row);source.put("title",row.get("name"));source.put("bodyHtml",text(row.get("description")));
            ComputeContentTranslationService.seedSource(source,all);
            row.put("translations",all);row.put("englishReady",ComputeBusinessTranslations.complete(all,"en",text(row.get("description"))));
        }
        return row;
    }

    public List<Map<String, Object>> list(String resource) {
        if ("currencies".equals(resource)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) currencies.options().get("channels");
            return rows;
        }
        return repository.list(resource).stream().map(row -> adminRecord(resource,row)).toList();
    }

    @Transactional
    public Map<String, Object> save(String resource, Map<String, Object> request) {
        if ("levels".equals(resource)) new ComputeMemberRelations(data.jdbc()).lock();
        if ("currencies".equals(resource)) return currencies.save(resource, request.get("id") == null ? null : number(request.get("id")), request);
        String table = TABLES.get(resource);
        if (table == null) throw new BusinessException("server.compute.notFound");
        Long id = request.get("id") == null ? null : number(request.get("id"));
        Map<String,Object> previous=id==null?null:data.required(table, id, true);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled(request.getOrDefault("enabled", true)));
        if (!"levels".equals(resource)) values.put("updated_at", now());
        if(Set.of("products","levels","models","providers").contains(resource)){
            String image=ComputeContentHtml.safeUrl(text(request.getOrDefault("imageUrl",previous==null?"":previous.get("imageUrl"))));
            if(image.length()>500)throw new BusinessException("server.compute.invalidInput");values.put("image_url",image);
        }
        switch (resource) {
            case "products" -> {
                values.put("name", requiredText(request, "name", 160));
                values.put("type", choice(request, "type", Set.of("GPU"), "GPU"));
                String currency = currencies.defaultCurrency();
                if (!currencies.hasActiveCurrency(currency))
                    throw new BusinessException("server.compute.unsupportedCurrency");
                values.put("currency", currency);
                String purchaseMode = choice(request,"purchaseMode",Set.of("AMOUNT","QUANTITY"),previous==null?"AMOUNT":text(previous.getOrDefault("purchaseMode","AMOUNT")));
                values.put("purchase_mode", purchaseMode);
                values.put("unit_price", "QUANTITY".equals(purchaseMode) ? amount(request.get("unitPrice"))
                        : nonnegative(fields("unitPrice",request.getOrDefault("unitPrice",previous==null?0:previous.getOrDefault("unitPrice",0))),"unitPrice"));
                values.put("max_purchase_amount",nonnegative(fields("limit",request.getOrDefault("maxPurchaseAmount",previous==null?0:previous.getOrDefault("maxPurchaseAmount",0))),"limit"));
                long maximumQuantity=request.get("maxPurchaseQuantity")==null?(previous==null?0:number(previous.getOrDefault("maxPurchaseQuantity",0))):number(request.get("maxPurchaseQuantity"));
                if(maximumQuantity<0||maximumQuantity>ComputeProductPurchasePolicy.MAX_QUANTITY)throw new BusinessException("server.compute.invalidInput");
                values.put("max_purchase_quantity",maximumQuantity);
                if ("AMOUNT".equals(purchaseMode)) {
                    values.put("min_amount", amount(request.get("minAmount")));
                    values.put("max_amount", amount(request.get("maxAmount")));
                    checkRange(values.get("min_amount"), values.get("max_amount"));
                } else {
                    // 份数模式不使用旧金额范围；保留历史配置，后续切回金额模式仍由表单明确确认。
                    values.put("min_amount", previous==null?BigDecimal.ONE:previous.get("minAmount"));
                    values.put("max_amount", previous==null?BigDecimal.ONE:previous.get("maxAmount"));
                }
                values.put("duration_days", integer(request, "durationDays", 1, 3650, 30));
                values.put("daily_rate_min", nonnegative(request, "dailyRateMin")); values.put("daily_rate_max", nonnegative(request, "dailyRateMax"));
                checkRange(values.get("daily_rate_min"), values.get("daily_rate_max"));
                if (decimal(values.get("daily_rate_max")).compareTo(new BigDecimal("100")) > 0
                        || decimal(values.get("daily_rate_min")).scale() > 8 || decimal(values.get("daily_rate_max")).scale() > 8) throw new BusinessException("server.compute.invalidRate");
                values.put("settlement_mode", choice(request, "settlementMode", Set.of("A", "B", "C", "D"), null));
                String productDescription=description(request.get("description"));
                // 保留已有 TEXT 字段定义，按 UTF-8 字节限制，避免中文或表情导致数据库截断。
                if(productDescription.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65_535)
                    throw new BusinessException("server.compute.content.tooLarge");
                values.put("description", productDescription);
            }
            case "models" -> {
                long provider=number(request.get("providerId"));
                requireProvider(provider);
                values.put("provider_id",provider); values.put("name", requiredText(request, "name", 160));
                values.put("input_price", nonnegative(request, "inputPrice")); values.put("output_price", nonnegative(request, "outputPrice"));
                values.put("volatility", nonnegative(request, "volatility"));
                if (decimal(values.get("volatility")).compareTo(new BigDecimal("100")) > 0 || decimal(values.get("volatility")).scale() > 4) throw new BusinessException("server.compute.invalidRate");
                values.put("current_input_price", values.get("input_price")); values.put("current_output_price", values.get("output_price"));
            }
            case "levels" -> {
                values.put("name", requiredText(request, "name", 100)); values.put("threshold", nonnegative(request, "threshold"));
                values.put("required_direct_members", integer(request, "requiredDirectMembers", 0, 1_000_000,
                        previous == null ? 0 : (int) number(previous.get("requiredDirectMembers"))));
                Map<String, Object> directThreshold = fields("directMemberDepositThreshold", request.getOrDefault("directMemberDepositThreshold",
                        previous == null ? BigDecimal.ZERO : previous.get("directMemberDepositThreshold")));
                values.put("direct_member_deposit_threshold", nonnegative(directThreshold, "directMemberDepositThreshold"));
                values.put("description",description(request.getOrDefault("description",previous==null?"":previous.get("description"))));
                if (id != null && id == 1 && !enabled(values.get("enabled"))) throw new BusinessException("server.compute.baseLevelProtected");
                values.put("auto_enabled",enabled(request.getOrDefault("autoEnabled",previous==null?false:previous.get("autoEnabled"))));
                Object automatic = request.getOrDefault("autoUpgradeEnabled", previous == null ? true : enabled(previous.get("autoUpgradeEnabled")));
                if (!(automatic instanceof Boolean)) throw new BusinessException("server.compute.invalidInput");
                values.put("auto_upgrade_enabled", automatic);
                values.put("vip_validity_days",integer(request,"vipValidityDays",0,36500,0));
                values.put("vip_cycle_policy",choice(request,"vipCyclePolicy",Set.of("RESET","KEEP"),"RESET"));
                values.put("auto_display_seconds",integer(request,"autoDisplaySeconds",1,60,15));
                values.put("vip_reward_mode",choice(request,"vipRewardMode",Set.of("RATE","FIXED"),"RATE"));
                values.put("vip_fixed_reward",nonnegative(request,"vipFixedReward"));
                int detailDelay=integer(request,"vipDetailsDelayMinutes",1,1380,1);
                values.put("vip_details_delay_minutes",detailDelay);
                values.put("auto_daily_limit",integer(request,"autoDailyLimit",1,1000,1));
                if (request.containsKey("autoCooldownHours")) {
                    BigDecimal hours = nonnegative(request,"autoCooldownHours");
                    if (hours.compareTo(BigDecimal.valueOf(8760)) > 0) throw new BusinessException("server.compute.invalidInput");
                    values.put("auto_cooldown_seconds",hours.multiply(BigDecimal.valueOf(3600)).setScale(0,java.math.RoundingMode.HALF_UP).intValueExact());
                } else values.put("auto_cooldown_seconds",integer(request,"autoCooldownSeconds",0,31_536_000,0));
                values.put("auto_click_rate_min",nonnegative(request,"autoClickRateMin"));
                values.put("auto_click_rate_max",nonnegative(request,"autoClickRateMax"));
                BigDecimal displayRate = decimal(request.getOrDefault("displayReturnRate", previous == null ? 0 : previous.getOrDefault("displayReturnRate", 0)));
                if (displayRate.signum() < 0 || displayRate.compareTo(new BigDecimal("100")) > 0 || displayRate.scale() > 8)
                    throw new BusinessException("server.compute.invalidRate");
                values.put("display_return_rate", displayRate);
                checkRange(values.get("auto_click_rate_min"),values.get("auto_click_rate_max"));
                if(decimal(values.get("auto_click_rate_max")).compareTo(new BigDecimal("100"))>0)
                    throw new BusinessException("server.compute.invalidRate");
                // 供应商绑定独立更新；未提交该字段时保留当前绑定。
                if (request.containsKey("autoProviderIds")) {
                    List<Long> providers = ComputeVipModelAllocation.normalizedIds(request.get("autoProviderIds"));
                    List<Long> existingProviders = previous == null ? List.of() : ComputeVipModelAllocation.providerIds(data, previous);
                    for (Long provider : providers) {
                        if (!existingProviders.contains(provider)) requireProvider(provider);
                        else if (repository.saveProviderRows(provider).isEmpty())
                            throw new BusinessException("server.compute.v6.providerRequired");
                    }
                    values.put("auto_provider_ids", data.json(providers));
                } else if (previous == null) values.put("auto_provider_ids", "[]");
                Object rewardRules=request.getOrDefault("rewardRules", previous==null?List.of():ComputeRewardPolicy.readVipRules(data,previous.get("rewardRulesJson")));
                List<Map<String,Object>> normalizedRewards=ComputeRewardPolicy.normalizeVipRules(rewardRules, supportedTeamDepth());
                values.put("reward_rules_json",data.json(normalizedRewards));
                if(normalizedRewards.stream().anyMatch(rule->enabled(rule.get("firstDepositEnabled"))||enabled(rule.get("depositEnabled")))) ComputeRewardPolicy.activateDepositEngine(data);
            }
            case "providers" -> {
                String code=requiredText(request,"code",80);
                if(!code.matches("[A-Za-z0-9_-]{1,80}"))throw new BusinessException("server.compute.invalidInput");
                values.put("code",code);values.put("name",requiredText(request,"name",100));
            }
            case "fee-rules" -> {
                ComputeWithdrawalLimitPolicy.ruleValues(request, previous, values);
                values.put("name", requiredText(request, "name", 100)); values.put("priority", integer(request, "priority", -100000, 100000, 0));
                String ruleType = choice(request, "ruleType", Set.of("GLOBAL", "PERSONAL", "LEVEL", "CHANNEL", "AMOUNT"), null);
                values.put("rule_type", ruleType);
                // 每类规则只保存对应目标；隐藏的旧表单值不能悄悄叠加适用范围。
                values.put("member_id", null); values.put("level", null);
                values.put("currency", "*"); values.put("network", "*");
                values.put("min_amount", BigDecimal.ZERO); values.put("max_amount", null);
                switch (ruleType) {
                    case "PERSONAL" -> values.put("member_id", repository.saveMemberRow(requiredText(request, "username", 100)).get("id"));
                    case "LEVEL" -> {
                        long levelId = number(request.get("level"));
                        if (repository.saveLevelCount(levelId) == 0)
                            throw new BusinessException("server.compute.invalidLevel");
                        values.put("level", levelId);
                    }
                    case "CHANNEL" -> {
                        var channel = repository.saveCurrencyRow(number(request.get("channelId")));
                        values.put("currency", channel.get("currency")); values.put("network", channel.get("network"));
                    }
                    case "AMOUNT" -> {
                        values.put("min_amount", nonnegative(request, "minAmount"));
                        values.put("max_amount", request.get("maxAmount") == null ? null : amount(request.get("maxAmount")));
                        if (values.get("max_amount") != null) checkRange(values.get("min_amount"), values.get("max_amount"));
                        else if (decimal(values.get("min_amount")).signum() == 0) throw new BusinessException("server.compute.invalidAmount");
                    }
                    default -> { }
                }
                for (String field : List.of("rate", "fixedFee", "minimumFee")) values.put(snake(field), nonnegative(request, field));
                if (decimal(values.get("rate")).compareTo(new BigDecimal("100")) >= 0 || decimal(values.get("rate")).scale() > 6) throw new BusinessException("server.compute.invalidRate");
                values.put("free_count", integer(request, "freeCount", 0, 100000, 0)); values.put("period", choice(request, "period", Set.of("WEEK", "MONTH"), "MONTH"));
                for (String field : List.of("openTime", "closeTime", "freeStartTime", "freeEndTime")) values.put(snake(field), time(request.get(field)));
                if ((values.get("open_time") == null) != (values.get("close_time") == null)
                        || (values.get("free_start_time") == null) != (values.get("free_end_time") == null)) throw new BusinessException("server.compute.invalidTimeWindow");
                values.put("open_weekdays", String.join(",", ComputeMoneyPolicy.openWeekdays(request.get("openWeekdays")).stream().map(String::valueOf).toList()));
            }
            case "transfer-rules" -> {
                values.put("from_wallet", choice(request, "fromWallet", Set.of("COMPUTE", "PROJECT", "EARNINGS"), null));
                values.put("to_wallet", choice(request, "toWallet", Set.of("COMPUTE", "PROJECT", "EARNINGS"), null));
                if (values.get("from_wallet").equals(values.get("to_wallet"))) throw new BusinessException("server.compute.sameWallet");
                values.put("currency", requiredText(request, "currency", 16));
                values.put("min_amount", nonnegative(request, "minAmount")); values.put("max_amount", amount(request.get("maxAmount")));
                checkRange(values.get("min_amount"), values.get("max_amount"));
                for (String field : List.of("rate", "fixedFee", "minimumFee")) values.put(snake(field), nonnegative(request, field));
                if (decimal(values.get("rate")).compareTo(new BigDecimal("100")) >= 0 || decimal(values.get("rate")).scale() > 6) throw new BusinessException("server.compute.invalidRate");
            }
            default -> throw new BusinessException("server.compute.notFound");
        }
        boolean multilingual=translation!=null&&Set.of("products","levels").contains(resource);
        boolean englishReady=true,sourceChanged=false;
        long version=previous==null?1:number(previous.getOrDefault("contentVersion",1));
        if(multilingual){
            if(text(values.get("description")).isEmpty())throw new BusinessException("server.compute.content.bodyRequired");
            String sourceLanguage=locales.normalize(text(request.getOrDefault("sourceLanguage",previous==null?"zh":previous.getOrDefault("sourceLanguage","zh"))));
            if(!Set.of("zh","en").contains(sourceLanguage))throw new BusinessException("server.compute.content.invalid");
            Map<String,Object> old=previous==null?new LinkedHashMap<>():translation.translationMap(previous.get("translationsJson"));
            Map<String,Object> all=ComputeBusinessTranslations.merge(old,request.get("translations"),request.get("clearTranslationLanguages"),locales);
            all.put(sourceLanguage,fields("title",values.get("name"),"bodyHtml",values.get("description")));
            englishReady=ComputeBusinessTranslations.complete(all,"en",text(values.get("description")));
            sourceChanged=previous!=null&&(!Objects.equals(text(previous.get("name")),text(values.get("name")))||!Objects.equals(text(previous.get("description")),text(values.get("description")))
                    ||!Objects.equals(ComputeBusinessTranslations.fields(old,"en"),ComputeBusinessTranslations.fields(all,"en")));
            if(sourceChanged)version++;
            values.put("translations_json",data.json(all));values.put("content_version",version);values.put("source_language",sourceLanguage);
            if(!englishReady)throw new BusinessException("server.compute.content.translation.englishRequired");
        }
        try {
            if (id == null) id = data.insert(table, values);
            else {
                List<Object> args = new ArrayList<>(values.values()); args.add(id);
                data.update(table, id, values);
            }
        } catch (DuplicateKeyException exception) { throw new BusinessException("server.compute.duplicateConfiguration"); }
        if(multilingual&&sourceChanged)translation.supersedeCatalog(resource,id,version);
        Map<String,Object> result=adminRecord(resource,data.required(table, id, false));
        return result;
    }

    private Map<String,Object> requireProvider(long id) {
        List<Map<String,Object>> rows=repository.requireProviderProviderRows(id);
        if(rows.isEmpty())throw new BusinessException("server.compute.v6.providerRequired");
        return rows.get(0);
    }

    public Map<String, Object> settings() {
        Map<String, Object> result = settingsValues(repository.settingsSettingRows());
        result.put("rewardEngineActive", repository.settingsRewardConfigurationCount() > 0);
        result.putAll(memberUids.adminSettings());
        return result;
    }

    public Map<String, Object> vipSettings() {
        return Map.of("levels", repository.vipSettings().stream().map(row -> fields("id", row.get("id"), "name", row.get("name"),
                "autoUpgradeEnabled", enabled(row.get("autoUpgradeEnabled")))).toList());
    }

    /** 只保存指定目标等级开关，不触发会员扫描、补升级或周期重置。 */
    @Transactional
    public Map<String,Object> saveVipSettings(Map<String,Object> request) {
        if (!request.keySet().equals(Set.of("id", "autoUpgradeEnabled")) || !(request.get("autoUpgradeEnabled") instanceof Boolean))
            throw new BusinessException("server.compute.invalidInput");
        long id = number(request.get("id"));
        new ComputeMemberRelations(data.jdbc()).lock();
        data.required("compute_level", id, true);
        data.update("compute_level", id, Map.of("auto_upgrade_enabled", request.get("autoUpgradeEnabled")));
        return vipSettings();
    }

    /** 展示与保存共用解析规则；保存必须传入加锁后的当前行，不能再次读取事务旧快照。 */
    private Map<String, Object> settingsValues(List<Map<String, Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = text(row.get("settingKey")), value = text(row.get("settingValue"));
            if (ComputeMemberUidAllocator.USERNAME_LIMIT.equals(key)) result.put(key, !"false".equalsIgnoreCase(value));
            else if (BOOLEAN_SETTINGS.contains(key)) result.put(key, enabled(value));
            else if (Set.of("teamSupportedDepth","addressLimit","inviteCodeLength").contains(key)) result.put(key, decimal(value).intValueExact());
            else if (Set.of("summaryCurrency","inviteCodeMode").contains(key)) result.put(key, value);
            else if ("loginMethodOrder".equals(key)) result.put(key, ComputeLoginMethodOrder.stored(value));
        }
        result.putIfAbsent("summaryCurrency", "USDT");
        result.putIfAbsent("addressLimit",10);
        result.putIfAbsent(ComputeMemberUidAllocator.USERNAME_LIMIT, true);
        result.putIfAbsent("withdrawalAddressUnique", true);
        result.putIfAbsent("withdrawalAddressDeleteEnabled", true);
        result.putIfAbsent("inviteCodeMode", ComputeInviteCodeService.DEFAULT_MODE);
        result.putIfAbsent("inviteCodeLength", ComputeInviteCodeService.DEFAULT_LENGTH);
        result.putIfAbsent("loginMethodOrder", ComputeLoginMethodOrder.DEFAULT);
        result.put("teamSupportedDepth", (int)Math.min(200, Math.max(1,
                number(result.getOrDefault("teamSupportedDepth", 3)))));
        return result;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> saveSettings(Map<String, Object> request) {
        for (String key : List.of("withdrawalAddressDeleteEnabled", ComputeMemberUidAllocator.USERNAME_LIMIT))
            if (request.containsKey(key) && !(request.get(key) instanceof Boolean))
                throw new BusinessException("server.compute.invalidInput");
        if (request.containsKey(ComputeMemberUidAllocator.USERNAME_LIMIT)
                || request.containsKey(ComputeMemberUidAllocator.BASE) || request.containsKey(ComputeMemberUidAllocator.STEP)) {
            // 身份配置与注册分配共用关系锁，保存本身不改动存量会员。
            new ComputeMemberRelations(data.jdbc()).lock();
        }
        // 全部开关作为一个配置事务校验，避免出现所有登录方式都停用的状态。
        Map<String, Object> merged = settingsValues(repository.saveSettingsSettingRows());
        for (String key : request.keySet()) {
            if ("rewardEngineActive".equals(key)) continue; // 只读状态，不接受客户端写入。
            if (Set.of(ComputeMemberUidAllocator.BASE, ComputeMemberUidAllocator.STEP).contains(key)) continue;
            if (BOOLEAN_SETTINGS.contains(key)) merged.put(key, enabled(request.get(key)));
            else if ("loginMethodOrder".equals(key)) merged.put(key, ComputeLoginMethodOrder.validate(request.get(key)));
            else if ("teamSupportedDepth".equals(key)) {
                int depth=integer(request,key,1,200,3);
                for(var level:repository.saveSettingsLevelRows())
                    for(var rule:ComputeRewardPolicy.readVipRules(data,level.get("rewardRulesJson")))
                        if(number(rule.get("depth"))>depth)throw new BusinessException("server.compute.v12.teamDepthInUse");
                merged.put(key,depth);
            }
            else if ("addressLimit".equals(key)) merged.put(key, integer(request,key,1,1000,10));
            else if (Set.of("inviteCodeMode", "inviteCodeLength").contains(key)) {
                if (request.get(key) == null) throw new BusinessException("server.compute.inviteCode.ruleInvalid");
                merged.put(key, request.get(key));
            }
            else if ("summaryCurrency".equals(key)) {
                String currency = text(request.get(key));
                if (!Set.of("USD", "USDT", "USDC").contains(currency))
                    throw new BusinessException("server.compute.summaryCurrencyInvalid");
                merged.put(key, ComputeUsdAccounting.WALLET_CURRENCY);
            }
            else throw new BusinessException("server.compute.invalidInput");
        }
        boolean anyEnabled = false;
        for (String field : List.of("username", "email", "phone")) {
            boolean on = enabled(merged.getOrDefault(field + "Enabled", true)); anyEnabled |= on;
            if (!on && enabled(merged.get(field + "Required"))) throw new BusinessException("server.compute.loginSettingsInvalid");
        }
        if (!anyEnabled) throw new BusinessException("server.compute.loginSettingsInvalid");
        var invitePolicy = ComputeInviteCodeService.policy(merged.get("inviteCodeMode"), merged.get("inviteCodeLength"));
        merged.put("inviteCodeMode", invitePolicy.mode()); merged.put("inviteCodeLength", invitePolicy.length());
        memberUids.configure(request);
        for (Map.Entry<String, Object> entry : merged.entrySet())
            repository.saveSettingsSettingWrite(entry.getKey(), text(entry.getValue()), now());
        return settings();
    }

    private int supportedTeamDepth() {
        return (int)Math.min(200,Math.max(1,number(data.setting("teamSupportedDepth","3"))));
    }

    public Map<String, Object> overview() {
        String currency = data.setting("summaryCurrency", "USDT");
        Map<String, Object> result = repository.overviewMemberCount();
        result.put("summaryCurrency", currency);
        result.putAll(repository.overviewMemberCount2(currency, currency));
        result.putAll(repository.overviewMemberCount3(currency));
        result.putAll(repository.overviewMemberCount4(currency));
        result.put("currencySummaries", repository.overviewCurrencyRows());
        return result;
    }

    public Map<String, Object> page(String resource, Long memberId, Map<String, String> query) {
        var result = repository.page(resource,memberId,query);
        @SuppressWarnings("unchecked") List<Map<String,Object>> records = (List<Map<String,Object>>) result.get("records");
        long total = number(result.get("total"));
        if("ledger".equals(resource)) ComputeLedgerDetails.enrich(data,records,null,memberId);
        ComputePaymentRecords.enrich(data, resource, records, currencies.gatewayEnabled());
        if (memberId != null && Set.of("deposits", "withdrawals").contains(resource)) {
            for (var record : records) {
                for (String privateField : List.of("gatewayFees", "gatewayReview", "gatewayReason", "submissionState", "ruleSnapshot", "addressSnapshot", "reviewedBy", "createdBy", "creationRemark")) record.remove(privateField);
            }
        }
        return fields("records", records, "total", total);
    }

    @Transactional
    public Map<String, Object> updateMember(long id, Map<String, Object> request) {
        repository.updateMemberMemberRow(id);
        if (!Set.of("level", "enabled", "password").containsAll(request.keySet()) || request.isEmpty()) throw new BusinessException("server.compute.invalidInput");
        Map<String, Object> values = new LinkedHashMap<>();
        if (request.containsKey("level")) {
            long level = number(request.get("level"));
            if (repository.updateMemberLevelCount(level) == 0) throw new BusinessException("server.compute.invalidLevel");
            values.put("level", level);
        }
        if (request.containsKey("enabled")) values.put("enabled", enabled(request.get("enabled")));
        if (request.containsKey("password") && !text(request.get("password")).isEmpty()) {
            String password = text(request.get("password")); ComputeCredentials.validatePassword(password); values.put("password_hash", passwords.hashPassword(password));
        }
        if (values.isEmpty()) throw new BusinessException("server.compute.invalidInput");
        values.put("updated_at", now()); List<Object> args = new ArrayList<>(values.values()); args.add(id);
        data.update("compute_member", id, values);
        return repository.updateMemberMemberRow2(id);
    }

    private static String requiredText(Map<String, Object> request, String key, int max) {
        String value = text(request.get(key)); if (value.isEmpty() || value.length() > max) throw new BusinessException("server.compute.invalidInput"); return value;
    }
    private static String description(Object value){return text(value).isEmpty()?"":ComputeContentHtml.sanitize(text(value));}
    private static String choice(Map<String, Object> request, String key, Set<String> options, String fallback) {
        String value = text(request.getOrDefault(key, fallback)); if (!options.contains(value)) throw new BusinessException("server.compute.invalidInput"); return value;
    }
    static int integer(Map<String, Object> request, String key, int min, int max, int fallback) {
        long value = request.get(key) == null ? fallback : number(request.get(key)); if (value < min || value > max) throw new BusinessException("server.compute.invalidInput"); return (int) value;
    }
    private static Object nullableId(Object value) { if (value == null || text(value).isEmpty()) return null; long id = number(value); if (id <= 0) throw new BusinessException("server.compute.invalidInput"); return id; }
    private static BigDecimal nonnegative(Map<String, Object> request, String key) {
        BigDecimal value = decimal(request.get(key)); if (value.signum() < 0 || value.scale() > 8 || value.precision() - value.scale() > 18) throw new BusinessException("server.compute.invalidAmount"); return value;
    }
    private static void checkRange(Object min, Object max) { if (decimal(min).compareTo(decimal(max)) > 0) throw new BusinessException("server.compute.invalidRange"); }
    private static String time(Object value) {
        if (text(value).isEmpty()) return null;
        try { return LocalTime.parse(text(value)).toString(); } catch (Exception exception) { throw new BusinessException("server.compute.invalidTimeWindow"); }
    }
    private static String snake(String value) { return value.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); }
}
