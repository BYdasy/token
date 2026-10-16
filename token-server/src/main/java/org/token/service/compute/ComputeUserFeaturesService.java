package org.token.service.compute;

import org.token.repository.compute.ComputeUserFeaturesRepository;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.tools.geo.IpUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 用户功能开关集中维护；公开投影不包含联系方式以外的管理数据。 */
@Service
public class ComputeUserFeaturesService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeUserFeaturesService(ComputeData data, ComputeAgentScope scope, ComputeUserFeaturesRepository repository) {
        this.data = data;
        this.scope = scope;
        this.repository = repository;
    }

    public ComputeUserFeaturesService(ComputeData data, ComputeAgentScope scope) {
        this(data, scope, new ComputeUserFeaturesRepository(data));
    }

    /** 与当前用户端 libphonenumber-js 支持的手机国家/地区目录同步。 */
    private static final Set<String> PHONE_COUNTRIES = Set.of(("AC,AD,AE,AF,AG,AI,AL,AM,AO,AR,AS,AT,AU,AW,AX,AZ,BA,BB,BD,BE,BF,BG,BH,BI,BJ,BL,BM,BN,BO,BQ,BR,BS,BT,BW,BY,BZ,CA,CC,CD,CF,CG,CH,CI,CK,CL,CM,CN,CO,CR,CU,CV,CW,CX,CY,CZ,DE,DJ,DK,DM,DO,DZ,EC,EE,EG,EH,ER,ES,ET,FI,FJ,FK,FM,FO,FR,GA,GB,GD,GE,GF,GG,GH,GI,GL,GM,GN,GP,GQ,GR,GT,GU,GW,GY,HK,HN,HR,HT,HU,ID,IE,IL,IM,IN,IO,IQ,IR,IS,IT,JE,JM,JO,JP,KE,KG,KH,KI,KM,KN,KP,KR,KW,KY,KZ,LA,LB,LC,LI,LK,LR,LS,LT,LU,LV,LY,MA,MC,MD,ME,MF,MG,MH,MK,ML,MM,MN,MO,MP,MQ,MR,MS,MT,MU,MV,MW,MX,MY,MZ,NA,NC,NE,NF,NG,NI,NL,NO,NP,NR,NU,NZ,OM,PA,PE,PF,PG,PH,PK,PL,PM,PR,PS,PT,PW,PY,QA,RE,RO,RS,RU,RW,SA,SB,SC,SD,SE,SG,SH,SI,SJ,SK,SL,SM,SN,SO,SR,SS,ST,SV,SX,SY,SZ,TA,TC,TD,TG,TH,TJ,TK,TL,TM,TN,TO,TR,TT,TV,TW,TZ,UA,UG,US,UY,UZ,VA,VC,VE,VG,VI,VN,VU,WF,WS,XK,YE,YT,ZA,ZM,ZW").split(","));
    /** 固定国家名称仅初始化一次；列表与条目均不可变，保持接口字段顺序。 */
    private static final List<Map<String, Object>> PHONE_COUNTRY_OPTIONS = countryOptions(PHONE_COUNTRIES);
    /** GeoIP 国家筛选覆盖完整 ISO 地区，兼容既有 AC/TA/XK；不读取平台私有设置。 */
    private static final List<Map<String,Object>> COUNTRY_OPTIONS = countryOptions(java.util.stream.Stream.concat(
            Arrays.stream(Locale.getISOCountries()),PHONE_COUNTRIES.stream()).collect(java.util.stream.Collectors.toSet()));

    private static List<Map<String,Object>> countryOptions(Collection<String> codes){return codes.stream().sorted().map(code -> {
        Locale locale = new Locale("", code);
        return Collections.unmodifiableMap(fields("code", code, "name", locale.getDisplayCountry(Locale.CHINESE),
                "englishName", locale.getDisplayCountry(Locale.ENGLISH)));
    }).toList();}

    public static List<Map<String,Object>> countryOptions(){return COUNTRY_OPTIONS;}

    private final ComputeData data;
    private final ComputeUserFeaturesRepository repository;
    private final ComputeAgentScope scope;

    public Map<String, Object> settings() {
        Map<String, Object> value = fields("supportEnabled", false, "supportUrl", "", "checkinEnabled", false,
                "dailyReward", "0", "cycleDays", 7, "cycleBonus", "0", "floatingEnabled", false,
                "floatingImage", "", "floatingActivityId", 0, "phoneAutoCountry", false, "phoneDefaultCountry", "CN");
        value.putAll(data.jsonMap(data.setting("userFeatures", "{}")));
        value.putIfAbsent("checkinFloatingEnabled", false);
        value.putIfAbsent("checkinFloatingImage", "");
        value.putIfAbsent("checkinFloatingPriority", 0);
        value.putIfAbsent("checkinFloatingBottom", 160);
        value.putIfAbsent("supportFloatingImage", "");
        value.putIfAbsent("supportFloatingScope", "HOME");
        value.putIfAbsent("supportFloatingBottom", 84);
        value.putIfAbsent("supportSalesmartlyScriptUrl", "");
        if (!value.containsKey("supportContacts")) value.put("supportContacts", text(value.get("supportUrl")).isBlank() ? List.of() :
                List.of(fields("name", "客服", "image", "", "url", value.get("supportUrl"), "enabled", true, "priority", 0)));
        value.put("invitationEnabled", Boolean.parseBoolean(data.setting("invitationEnabled", "true")));
        return value;
    }

    public Map<String, Object> adminSettings() {
        scope.requireGlobalScope();
        var result = settings();
        long activityId = number(result.get("floatingActivityId"));
        var activity = activityId > 0 ? repository.adminSettingsActivityRows(activityId) : List.<Map<String, Object>>of();
        result.put("floatingActivityName", activity.isEmpty() ? "" : activity.get(0).get("name"));
        result.put("phoneCountries", PHONE_COUNTRY_OPTIONS);
        return result;
    }

    public Map<String,Object> checkinSettings() {
        scope.requireGlobalScope();var all=settings();var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("checkinEnabled","dailyReward","cycleDays","cycleBonus","checkinFloatingEnabled","checkinFloatingImage","checkinFloatingPriority","checkinFloatingBottom"))result.put(key,all.get(key));
        return result;
    }

    @Transactional
    public void save(Map<String, Object> input) {
        scope.requireGlobalScope();
        Map<String, Object> value = lockedSettings();
        value.put("phoneAutoCountry", bool(input.get("phoneAutoCountry")));
        String country = text(input.get("phoneDefaultCountry")).toUpperCase(Locale.ROOT);
        if (!PHONE_COUNTRIES.contains(country)) invalid();
        value.put("phoneDefaultCountry", country);
        put("userFeatures", data.json(value));
        put("invitationEnabled", String.valueOf(bool(input.get("invitationEnabled"))));
    }

    /** 部分配置写入前锁住同一行，避免客服与签到表单互相覆盖。 */
    private Map<String,Object> lockedSettings() {
        var current=repository.lockFeatureSettings();
        var value=settings();
        // 范围检查可能已建立RR快照；必须以锁定行的当前值合并，而非再次读取旧快照。
        if(!current.isEmpty())value.putAll(data.jsonMap(current.get(0)));
        value.remove("invitationEnabled"); return value;
    }

    @Transactional
    public void saveSupport(Map<String,Object> input) {
        scope.requireGlobalScope();
        var value=lockedSettings();
        if (!(input.get("supportContacts") instanceof List<?> rows) || rows.size()>50) invalid();
        List<Map<String,Object>> contacts=new ArrayList<>();
        for (Object raw:(List<?>)input.get("supportContacts")) {
            if (!(raw instanceof Map<?,?>)) invalid();
            Map<?,?> row=(Map<?,?>)raw;
            String name=text(row.get("name")).trim(), address=url(row.get("url"),false);
            if(name.isBlank()||name.length()>100||address.isBlank())invalid();
            contacts.add(fields("name",name,"url",address,"image",url(row.get("image"),true),"enabled",bool(row.get("enabled")),"priority",priority(row.get("priority"))));
        }
        value.put("supportEnabled",bool(input.get("supportEnabled")));value.put("supportContacts",contacts);
        value.put("supportFloatingImage",url(input.getOrDefault("supportFloatingImage",value.get("supportFloatingImage")),true));
        String floatingScope=text(input.getOrDefault("supportFloatingScope",value.get("supportFloatingScope")));
        if(!Set.of("HOME","ALL").contains(floatingScope))invalid();
        value.put("supportFloatingScope",floatingScope);
        value.put("supportFloatingBottom",floatingBottom(input.getOrDefault("supportFloatingBottom",value.get("supportFloatingBottom"))));
        String salesmartlyUrl = salesmartlyUrl(input.getOrDefault("supportSalesmartlyScriptUrl", value.get("supportSalesmartlyScriptUrl")));
        value.put("supportSalesmartlyScriptUrl", salesmartlyUrl);
        if(bool(value.get("supportEnabled"))&&contacts.stream().noneMatch(row->bool(row.get("enabled"))))invalid();
        put("userFeatures",data.json(value));
    }

    @Transactional
    public void saveCheckin(Map<String,Object> input) {
        scope.requireGlobalScope();var value=lockedSettings();
        value.put("checkinEnabled",bool(input.get("checkinEnabled")));
        for (String key : List.of("dailyReward", "cycleBonus")) {
            BigDecimal reward = decimal(input.get(key));
            if (reward.signum() < 0 || reward.scale() > 8 || reward.compareTo(new BigDecimal("1000000")) > 0) invalid();
            value.put(key, reward.toPlainString());
        }
        BigDecimal days = decimal(input.get("cycleDays"));
        if (days.compareTo(BigDecimal.ONE) < 0 || days.compareTo(new BigDecimal("366")) > 0 || days.stripTrailingZeros().scale() > 0) invalid();
        value.put("cycleDays", days.intValue());
        value.put("checkinFloatingEnabled",bool(input.get("checkinFloatingEnabled")));
        value.put("checkinFloatingImage",url(input.get("checkinFloatingImage"),true));
        value.put("checkinFloatingPriority",priority(input.get("checkinFloatingPriority")));
        value.put("checkinFloatingBottom",floatingBottom(input.getOrDefault("checkinFloatingBottom",value.get("checkinFloatingBottom"))));
        if(bool(value.get("checkinFloatingEnabled"))&&text(value.get("checkinFloatingImage")).isBlank())invalid();
        put("userFeatures", data.json(value));
    }

    private int priority(Object raw) {
        BigDecimal number=decimal(raw);if(number.scale()>0&&number.stripTrailingZeros().scale()>0||number.abs().compareTo(BigDecimal.valueOf(1000000))>0)invalid();return number.intValue();
    }

    private int floatingBottom(Object raw) {
        BigDecimal number=decimal(raw);
        if(number.signum()<0||number.compareTo(BigDecimal.valueOf(2000))>0||number.stripTrailingZeros().scale()>0)invalid();
        return number.intValue();
    }

    private void put(String key, String value) {
        repository.putSettingWrite(key, value, LocalDateTime.now(ZoneOffset.UTC));
    }

    public Map<String, Object> publicSettings(String clientIp) {
        return publicSettings(clientIp,"en");
    }

    public Map<String, Object> publicSettings(String clientIp,String language) {
        Map<String, Object> value = settings();
        String country = text(value.get("phoneDefaultCountry"));
        if (bool(value.get("phoneAutoCountry"))) {
            String resolved = IpUtils.getCountryCode(clientIp);
            if (resolved != null && PHONE_COUNTRIES.contains(resolved.toUpperCase(Locale.ROOT))) country = resolved.toUpperCase(Locale.ROOT);
        }
        value.put("phoneCountry", country);
        @SuppressWarnings("unchecked") List<Map<String,Object>> contacts=(List<Map<String,Object>>)value.get("supportContacts");
        value.put("supportContacts",!bool(value.get("supportEnabled"))?List.of():contacts.stream().filter(row->bool(row.get("enabled"))).sorted(Comparator.comparingLong(row->-number(row.get("priority")))).toList());
        value.remove("supportUrl");value.remove("floatingActivityId");value.remove("floatingImage");value.remove("floatingEnabled");
        List<Map<String,Object>> floating=new ArrayList<>();
        List<Map<String,Object>> banners=new ArrayList<>();
        long wheelId=number(data.setting("wheelActivityId","0"));
        LocalDateTime current=now();
        for(var row:repository.publicSettingsActivityRows(current, current, wheelId)) {
            var config=row.get("wheelConfig")==null?Map.<String,Object>of():data.jsonMap(row.get("wheelConfig"));
            if(bool(row.get("floatingEnabled")))
                floating.add(fields("key","ACTIVITY:"+row.get("id"),"path","/activities/"+row.get("id"),"image",row.get("floatingImage"),"priority",row.get("floatingPriority"),"bottom",config.getOrDefault("floatingBottom",160),"endsAt",row.get("endsAt")));
            String bannerImage=text(config.get("bannerImage"));
            if(!bannerImage.isBlank())banners.add(fields("key","ACTIVITY:"+row.get("id"),"path","/activities/"+row.get("id"),"image",bannerImage,"title",activityTitle(row,config,language),"endsAt",row.get("endsAt")));
        }
        if(bool(value.get("checkinEnabled"))&&bool(value.get("checkinFloatingEnabled")))
            floating.add(fields("key","CHECKIN","path","/checkin","image",value.get("checkinFloatingImage"),"priority",value.get("checkinFloatingPriority"),"bottom",value.get("checkinFloatingBottom")));
        floating.sort(Comparator.comparingLong(row->-number(row.get("priority"))));value.put("floatingEntries",floating);
        value.put("activityBanners",banners);
        return value;
    }

    /** 横幅只返回当前语言标题，不公开活动抽奖配置；缺译遵循英文回退。 */
    private String activityTitle(Map<String,Object> activity,Map<String,Object> config,String language) {
        String code=text(language).replace('_','-').toLowerCase(Locale.ROOT);
        Map<?,?> translations=config.get("translations") instanceof Map<?,?> values?values:Map.of();
        LinkedHashSet<String> candidates=new LinkedHashSet<>(List.of(code,code.split("-",2)[0]));
        if(code.equals("zh")||code.startsWith("zh-")) {
            for(String candidate:candidates)if(translations.get(candidate) instanceof Map<?,?> value&&!text(value.get("title")).isBlank())return text(value.get("title"));
            if(!text(activity.get("name")).isBlank())return text(activity.get("name"));
        }
        candidates.add("en");
        for(String candidate:candidates)if(translations.get(candidate) instanceof Map<?,?> value&&!text(value.get("title")).isBlank())return text(value.get("title"));
        return "Activity";
    }

    private static boolean bool(Object value) { return enabled(value); }

    public Map<String, Object> memberPreferences(long memberId) {
        return repository.memberPreferencesMemberRow(memberId);
    }

    @Transactional
    public void language(long memberId, String language) {
        String code = text(language).trim();
        if (repository.languageTranslateLanguageCount(code) != 1) invalid();
        repository.languageMemberWrite(code, memberId, code);
    }

    private String url(Object raw, boolean image) {
        String value = text(raw).trim();
        if (value.isEmpty()) return value;
        try {
            URI uri = URI.create(value);
            boolean relative = image && value.startsWith("/") && !value.startsWith("//") && !value.contains("\\");
            boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            if (value.length() > 2000 || (!relative && (!web || uri.getHost() == null || uri.getUserInfo() != null))) invalid();
            return value;
        } catch (IllegalArgumentException ex) { throw new BusinessException("server.compute.v11.featuresInvalid"); }
    }

    private void invalid() { throw new BusinessException("server.compute.v11.featuresInvalid"); }

    /** 只接收官方项目脚本地址，不存储或执行管理员粘贴的任意HTML/JavaScript。 */
    private String salesmartlyUrl(Object raw) {
        String value = text(raw).trim();
        if (value.isEmpty()) return value;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            // 官方安装地址可更换CDN子域名和文件名，不绑定某个商户项目编号。
            if (value.length() > 2000 || value.matches("(?s).*[\\s\\\\].*")
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || !(host.equals("salesmartly.com") || host.endsWith(".salesmartly.com"))
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/js/[A-Za-z0-9_-]+\\.js"))
                throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalidUrl) {
            throw new BusinessException("server.compute.v28Support.scriptInvalid");
        }
    }
}
