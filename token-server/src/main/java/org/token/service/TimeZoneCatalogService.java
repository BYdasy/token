package org.token.service;

import org.token.model.response.TimeZoneOptionView;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * 提供 IANA 时区的轻量远程搜索，避免前端一次渲染数百个选项。
 * 国家、地区和城市使用项目维护的中文索引；JDK 的中文名称只作为补充，避免不同 JDK 版本造成搜索差异。
 */
@Service
public class TimeZoneCatalogService {

    private static final Locale CHINESE = Locale.SIMPLIFIED_CHINESE;
    /** 未搜索时最多返回 12 项，任何前端参数都不能把它提升为完整列表。 */
    private static final int DEFAULT_LIMIT = 12;
    /** 搜索结果也限制数量，避免宽泛关键词让选择器一次渲染数百项。 */
    private static final int MAX_SEARCH_LIMIT = 24;

    private static final List<String> PREFERRED_ZONE_IDS = List.of(
            "Asia/Shanghai",
            "Asia/Hong_Kong",
            "Asia/Macau",
            "Asia/Taipei",
            "Asia/Singapore",
            "Asia/Tokyo",
            "Asia/Seoul",
            "Asia/Bangkok",
            "Asia/Kuala_Lumpur",
            "Asia/Dubai",
            "Europe/London",
            "Europe/Paris",
            "Europe/Berlin",
            "America/New_York",
            "America/Chicago",
            "America/Denver",
            "America/Los_Angeles",
            "America/Mexico_City",
            "America/Toronto",
            "America/Vancouver",
            "Australia/Sydney",
            "Pacific/Auckland",
            "UTC"
    );

    private static final Set<String> MEXICO_ZONE_IDS = Set.of(
            "America/Bahia_Banderas",
            "America/Cancun",
            "America/Chihuahua",
            "America/Ciudad_Juarez",
            "America/Hermosillo",
            "America/Matamoros",
            "America/Mazatlan",
            "America/Merida",
            "America/Mexico_City",
            "America/Monterrey",
            "America/Ojinaga",
            "America/Tijuana",
            "Mexico/BajaNorte",
            "Mexico/BajaSur",
            "Mexico/General"
    );

    /**
     * 标签中的中文国家或地区，也是中文检索的稳定索引，不依赖操作系统和 JDK 语言包。
     * 此处优先覆盖后台常用时区；同一国家的兼容别名由下方规则补齐。
     */
    private static final Map<String, String> CHINESE_COUNTRIES = Map.ofEntries(
            Map.entry("Asia/Shanghai", "中国大陆"),
            Map.entry("Asia/Urumqi", "中国大陆"),
            Map.entry("Asia/Hong_Kong", "中国香港"),
            Map.entry("Asia/Macau", "中国澳门"),
            Map.entry("Asia/Taipei", "中国台湾"),
            Map.entry("Asia/Singapore", "新加坡"),
            Map.entry("Asia/Tokyo", "日本"),
            Map.entry("Asia/Seoul", "韩国"),
            Map.entry("Asia/Pyongyang", "朝鲜"),
            Map.entry("Asia/Bangkok", "泰国"),
            Map.entry("Asia/Kuala_Lumpur", "马来西亚"),
            Map.entry("Asia/Kuching", "马来西亚"),
            Map.entry("Asia/Jakarta", "印度尼西亚"),
            Map.entry("Asia/Manila", "菲律宾"),
            Map.entry("Asia/Ho_Chi_Minh", "越南"),
            Map.entry("Asia/Kolkata", "印度"),
            Map.entry("Asia/Dubai", "阿联酋"),
            Map.entry("Asia/Jerusalem", "以色列"),
            Map.entry("Asia/Riyadh", "沙特阿拉伯"),
            Map.entry("Europe/London", "英国"),
            Map.entry("Europe/Paris", "法国"),
            Map.entry("Europe/Berlin", "德国"),
            Map.entry("Europe/Madrid", "西班牙"),
            Map.entry("Europe/Rome", "意大利"),
            Map.entry("Europe/Moscow", "俄罗斯"),
            Map.entry("Europe/Amsterdam", "荷兰"),
            Map.entry("Europe/Brussels", "比利时"),
            Map.entry("Europe/Zurich", "瑞士"),
            Map.entry("America/New_York", "美国"),
            Map.entry("America/Detroit", "美国"),
            Map.entry("America/Chicago", "美国"),
            Map.entry("America/Denver", "美国"),
            Map.entry("America/Phoenix", "美国"),
            Map.entry("America/Los_Angeles", "美国"),
            Map.entry("America/Anchorage", "美国"),
            Map.entry("Pacific/Honolulu", "美国"),
            Map.entry("America/Toronto", "加拿大"),
            Map.entry("America/Vancouver", "加拿大"),
            Map.entry("America/Edmonton", "加拿大"),
            Map.entry("America/Winnipeg", "加拿大"),
            Map.entry("America/Halifax", "加拿大"),
            Map.entry("America/St_Johns", "加拿大"),
            Map.entry("America/Sao_Paulo", "巴西"),
            Map.entry("America/Buenos_Aires", "阿根廷"),
            Map.entry("America/Santiago", "智利"),
            Map.entry("America/Bogota", "哥伦比亚"),
            Map.entry("America/Lima", "秘鲁"),
            Map.entry("Australia/Sydney", "澳大利亚"),
            Map.entry("Australia/Melbourne", "澳大利亚"),
            Map.entry("Australia/Brisbane", "澳大利亚"),
            Map.entry("Australia/Perth", "澳大利亚"),
            Map.entry("Australia/Adelaide", "澳大利亚"),
            Map.entry("Pacific/Auckland", "新西兰"),
            Map.entry("Pacific/Fiji", "斐济")
    );

    private static final Map<String, String> CHINESE_LOCATIONS = Map.ofEntries(
            Map.entry("Asia/Shanghai", "上海"),
            Map.entry("Asia/Urumqi", "乌鲁木齐"),
            Map.entry("Asia/Hong_Kong", "香港"),
            Map.entry("Asia/Macau", "澳门"),
            Map.entry("Asia/Taipei", "台北"),
            Map.entry("Asia/Singapore", "新加坡"),
            Map.entry("Asia/Tokyo", "东京"),
            Map.entry("Asia/Seoul", "首尔"),
            Map.entry("Asia/Bangkok", "曼谷"),
            Map.entry("Asia/Kuala_Lumpur", "吉隆坡"),
            Map.entry("Asia/Kuching", "古晋"),
            Map.entry("Asia/Jakarta", "雅加达"),
            Map.entry("Asia/Manila", "马尼拉"),
            Map.entry("Asia/Ho_Chi_Minh", "胡志明市"),
            Map.entry("Asia/Kolkata", "加尔各答"),
            Map.entry("Asia/Dubai", "迪拜"),
            Map.entry("Asia/Jerusalem", "耶路撒冷"),
            Map.entry("Asia/Riyadh", "利雅得"),
            Map.entry("Europe/London", "伦敦"),
            Map.entry("Europe/Paris", "巴黎"),
            Map.entry("Europe/Berlin", "柏林"),
            Map.entry("Europe/Madrid", "马德里"),
            Map.entry("Europe/Rome", "罗马"),
            Map.entry("Europe/Moscow", "莫斯科"),
            Map.entry("America/New_York", "纽约"),
            Map.entry("America/Chicago", "芝加哥"),
            Map.entry("America/Denver", "丹佛"),
            Map.entry("America/Phoenix", "菲尼克斯"),
            Map.entry("America/Los_Angeles", "洛杉矶"),
            Map.entry("America/Mexico_City", "墨西哥城"),
            Map.entry("America/Cancun", "坎昆"),
            Map.entry("America/Chihuahua", "奇瓦瓦"),
            Map.entry("America/Ciudad_Juarez", "华雷斯城"),
            Map.entry("America/Hermosillo", "埃莫西约"),
            Map.entry("America/Matamoros", "马塔莫罗斯"),
            Map.entry("America/Mazatlan", "马萨特兰"),
            Map.entry("America/Merida", "梅里达"),
            Map.entry("America/Monterrey", "蒙特雷"),
            Map.entry("America/Ojinaga", "奥希纳加"),
            Map.entry("America/Tijuana", "蒂华纳"),
            Map.entry("America/Toronto", "多伦多"),
            Map.entry("America/Vancouver", "温哥华"),
            Map.entry("America/Sao_Paulo", "圣保罗"),
            Map.entry("America/Buenos_Aires", "布宜诺斯艾利斯"),
            Map.entry("America/Santiago", "圣地亚哥"),
            Map.entry("America/Bogota", "波哥大"),
            Map.entry("America/Lima", "利马"),
            Map.entry("Australia/Sydney", "悉尼"),
            Map.entry("Australia/Melbourne", "墨尔本"),
            Map.entry("Australia/Brisbane", "布里斯班"),
            Map.entry("Australia/Perth", "珀斯"),
            Map.entry("Australia/Adelaide", "阿德莱德"),
            Map.entry("Pacific/Auckland", "奥克兰")
    );

    private static final Map<String, String> AREA_NAMES = Map.of(
            "Africa", "非洲",
            "America", "美洲",
            "Antarctica", "南极洲",
            "Arctic", "北极地区",
            "Asia", "亚洲",
            "Atlantic", "大西洋地区",
            "Australia", "澳大利亚",
            "Europe", "欧洲",
            "Indian", "印度洋地区",
            "Pacific", "太平洋地区"
    );

    private final List<CatalogEntry> catalog;
    private final Map<String, CatalogEntry> catalogById;

    public TimeZoneCatalogService() {
        Map<String, Integer> preferenceOrder = new HashMap<>();
        for (int index = 0; index < PREFERRED_ZONE_IDS.size(); index++) {
            preferenceOrder.put(PREFERRED_ZONE_IDS.get(index), index);
        }

        List<CatalogEntry> entries = ZoneId.getAvailableZoneIds().stream()
                .map(id -> createEntry(id, preferenceOrder.getOrDefault(id, Integer.MAX_VALUE)))
                .sorted(Comparator.comparingInt(CatalogEntry::preference)
                        .thenComparing(CatalogEntry::id))
                .toList();
        this.catalog = entries;
        Map<String, CatalogEntry> byId = new HashMap<>();
        entries.forEach(entry -> byId.put(entry.id(), entry));
        this.catalogById = Map.copyOf(byId);
    }

    /**
     * 查询时区。未输入关键词时只返回常用时区；输入关键词后可搜索全部 IANA 时区。
     * selected 用于保证编辑表单中的当前值始终有可读标签。
     */
    public List<TimeZoneOptionView> search(String query, Integer limit, String selected) {
        List<String> queryTerms = queryTerms(query);
        boolean searching = !queryTerms.isEmpty();
        if (!searching && !StringUtils.hasText(selected)) {
            return List.of();
        }
        int requestedLimit = limit == null ? (searching ? MAX_SEARCH_LIMIT : DEFAULT_LIMIT) : Math.max(1, limit);
        int actualLimit = Math.min(requestedLimit, searching ? MAX_SEARCH_LIMIT : DEFAULT_LIMIT);
        LinkedHashMap<String, CatalogEntry> matched = new LinkedHashMap<>();

        if (StringUtils.hasText(selected)) {
            CatalogEntry selectedEntry = catalogById.get(normalizeZoneId(selected));
            if (selectedEntry != null && (!searching || selectedEntry.matches(queryTerms))) {
                matched.put(selectedEntry.id(), selectedEntry);
            }
        }

        for (CatalogEntry entry : catalog) {
            if (matched.size() >= actualLimit) {
                break;
            }
            boolean isMatch = searching
                    ? entry.matches(queryTerms)
                    : false;
            if (isMatch) {
                matched.putIfAbsent(entry.id(), entry);
            }
        }

        Instant now = Instant.now();
        List<TimeZoneOptionView> result = new ArrayList<>(matched.size());
        matched.values().stream()
                .limit(actualLimit)
                .map(entry -> toView(entry, now))
                .forEach(result::add);
        return result;
    }

    private CatalogEntry createEntry(String id, int preference) {
        TimeZone timeZone = TimeZone.getTimeZone(id);
        String standardName = timeZone.getDisplayName(false, TimeZone.LONG, CHINESE);
        String daylightName = timeZone.getDisplayName(true, TimeZone.LONG, CHINESE);
        String location = CHINESE_LOCATIONS.getOrDefault(id, "");
        String country = resolveCountry(id);
        String area = AREA_NAMES.getOrDefault(id.substring(0, Math.max(0, id.indexOf('/'))), "");
        String aliases = resolveAliases(id);
        String searchText = normalize(String.join(" ",
                id,
                id.replace('_', ' '),
                standardName,
                daylightName,
                location,
                country,
                area,
                aliases,
                "时区 时差 UTC"
        ));
        return new CatalogEntry(id, preference, country, location, standardName, daylightName, searchText);
    }

    private TimeZoneOptionView toView(CatalogEntry entry, Instant now) {
        ZoneId zoneId = ZoneId.of(entry.id());
        boolean daylight = zoneId.getRules().isDaylightSavings(now);
        String zoneName = daylight ? entry.daylightName() : entry.standardName();
        ZoneOffset offset = zoneId.getRules().getOffset(now);
        String offsetText = offset.equals(ZoneOffset.UTC) ? "UTC+00:00" : "UTC" + offset.getId();
        List<String> labelParts = new ArrayList<>();
        addDistinctLabel(labelParts, entry.country());
        addDistinctLabel(labelParts, entry.location());
        addDistinctLabel(labelParts, zoneName + "（" + offsetText + "）");
        addDistinctLabel(labelParts, entry.id());
        String label = String.join("｜", labelParts);
        return new TimeZoneOptionView(entry.id(), label, offsetText);
    }

    private String resolveCountry(String id) {
        if (MEXICO_ZONE_IDS.contains(id) || id.startsWith("Mexico/")) {
            return "墨西哥";
        }
        if (id.startsWith("US/")) {
            return "美国";
        }
        if (id.startsWith("Canada/")) {
            return "加拿大";
        }
        if (id.startsWith("Brazil/")) {
            return "巴西";
        }
        if (id.startsWith("Chile/")) {
            return "智利";
        }
        if (id.startsWith("Australia/")) {
            return "澳大利亚";
        }
        return CHINESE_COUNTRIES.getOrDefault(id, "");
    }

    private String resolveAliases(String id) {
        return switch (id) {
            case "Asia/Shanghai" -> "中国 北京 重庆 广州 深圳 大陆 内地 中国标准时间 北京时间";
            case "Asia/Hong_Kong" -> "香港 香港特别行政区 港区 中国香港";
            case "Asia/Macau" -> "澳门 澳门特别行政区 澳区 中国澳门";
            case "Asia/Taipei" -> "台湾 台湾地区 中国台湾 台北";
            case "Asia/Kolkata" -> "印度 加尔各答 新德里 孟买";
            case "Europe/London" -> "英国 英格兰 伦敦 格林尼治";
            case "America/New_York" -> "美国 美东 纽约 华盛顿 波士顿";
            case "America/Chicago" -> "美国 美中 芝加哥";
            case "America/Denver" -> "美国 山地 丹佛";
            case "America/Los_Angeles" -> "美国 美西 洛杉矶 旧金山 西雅图";
            case "America/Mexico_City" -> "墨西哥 墨西哥城 中部";
            case "UTC", "Etc/UTC" -> "世界协调时间 协调世界时 零时区 格林尼治";
            default -> MEXICO_ZONE_IDS.contains(id) ? "墨西哥 墨西哥合众国" : "";
        };
    }

    private void addDistinctLabel(List<String> parts, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        boolean alreadyCovered = parts.stream()
                .anyMatch(existing -> existing.equals(value) || existing.contains(value));
        if (!alreadyCovered) {
            parts.add(value);
        }
    }

    private String normalizeZoneId(String value) {
        try {
            return ZoneId.of(value.trim()).getId();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace('_', ' ')
                        .replace('-', ' ')
                        .replace('/', ' ')
                        .replace('｜', ' ')
                        .replace('（', ' ')
                        .replace('）', ' ')
                        .replace('(', ' ')
                        .replace(')', ' ')
                        .replaceAll("\\s+", " ");
    }

    private List<String> queryTerms(String query) {
        String normalized = normalize(query);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        return Arrays.stream(normalized.split(" "))
                .map(term -> term.endsWith("时区") && term.length() > 2
                        ? term.substring(0, term.length() - 2)
                        : term)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private record CatalogEntry(
            String id,
            int preference,
            String country,
            String location,
            String standardName,
            String daylightName,
            String searchText
    ) {
        private boolean matches(List<String> terms) {
            return terms.stream().allMatch(searchText::contains);
        }
    }
}
