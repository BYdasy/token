package org.token.tools.geo;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.token.model.response.Location;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ua_parser.Client;
import ua_parser.Parser;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * IP 工具类
 *
 * 功能：
 * 1. 获取客户端 IP
 * 2. 判断 IPv4 / IPv6
 * 3. 根据 IP 查询国家、省份、城市
 * 4. 解析客户端设备信息
 * 5. 根据 IP 经纬度查询天气
 *
 * 注意：
 * 对外提供静态方法，便于认证日志和操作日志复用。
 */
@Slf4j
@Component
public class IpUtils {

    private static final String LOCAL_IP = "127.0.0.1";
    private static final String IPV6_LOOPBACK = "::1";
    private static final String IPV6_LOOPBACK_FULL = "0:0:0:0:0:0:0:1";

    private static final String DEFAULT_WINDOWS_CITY_DB_PATH = "E:/Java/GeoLite2-City.mmdb";
    private static final String DEFAULT_LINUX_CITY_DB_PATH = "/www/wwwroot/data/in/GeoLite2-City.mmdb";

    /**
     * GeoLite2 支持的语言。
     */
    private static final Set<String> ALLOWED_IP_LANGUAGES = Set.of(
            "de", "ru", "pt-BR", "ja", "en", "fr", "zh-CN", "es"
    );

    /**
     * IPv4 正则。
     */
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.|$)){4}$"
    );

    /**
     * IPv6 正则。
     */
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^(?:[A-F0-9]{1,4}:){7}[A-F0-9]{1,4}$"
                    + "|^(?:[A-F0-9]{1,4}:){1,6}:[A-F0-9]{1,4}$"
                    + "|^(?:[A-F0-9]{1,4}:){1,5}(:[A-F0-9]{1,4}){1,2}$"
                    + "|^(?:[A-F0-9]{1,4}:){1,4}(:[A-F0-9]{1,4}){1,3}$"
                    + "|^(?:[A-F0-9]{1,4}:){1,3}(:[A-F0-9]{1,4}){1,4}$"
                    + "|^(?:[A-F0-9]{1,4}:){1,2}(:[A-F0-9]{1,4}){1,5}$"
                    + "|^[A-F0-9]{1,4}:((:[A-F0-9]{1,4}){1,6})$"
                    + "|^:((:[A-F0-9]{1,4}){1,7}|:)$"
                    + "|^(?:[A-F0-9]{1,4}:){7}:$"
                    + "|^::(?:[A-F0-9]{1,4}:){0,5}[A-F0-9]{1,4}$"
                    + "|^::$"
                    + "|^(?:[A-F0-9]{1,4}:){1,7}:$",
            Pattern.CASE_INSENSITIVE
    );

    private static final List<String> IP_HEADERS = List.of(
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    );

    private static final Parser UA_PARSER = new Parser();

    private static volatile DatabaseReader databaseReader;

    private static String cityDbPath = resolveCityDbPath(
            null,
            DEFAULT_WINDOWS_CITY_DB_PATH,
            DEFAULT_LINUX_CITY_DB_PATH
    );

    private static String localTestIp;

    /**
     * 手动指定 GeoLite2 数据库路径。
     *
     * 如果配置了这个值，会优先使用这个路径。
     * 如果不配置，则自动根据系统区分 Windows / Linux。
     */
    @Value("${exp.geoip.city-db-path:}")
    private String configCityDbPath;

    /**
     * Windows 环境 GeoLite2 数据库路径。
     *
     * 注意：
     * Windows 下也可以使用 E:/Java/GeoLite2-City.mmdb 这种写法，
     * 比 E:\\Java\\GeoLite2-City.mmdb 更不容易因为转义出错。
     */
    @Value("${exp.geoip.city-db-windows-path:E:/Java/GeoLite2-City.mmdb}")
    private String configCityDbWindowsPath;

    /**
     * Linux 环境 GeoLite2 数据库路径。
     */
    @Value("${exp.geoip.city-db-linux-path:/www/wwwroot/data/in/GeoLite2-City.mmdb}")
    private String configCityDbLinuxPath;

    @Value("${exp.geoip.local-test-ip:}")
    private String configLocalTestIp;

    /**
     * 初始化配置。
     */
    @PostConstruct
    public void init() {
        cityDbPath = resolveCityDbPath(
                configCityDbPath,
                configCityDbWindowsPath,
                configCityDbLinuxPath
        );

        localTestIp = configLocalTestIp;
        initDatabaseReader();

        log.info(
                "IpUtils initialized. osName: {}, geoipDbPath: {}",
                System.getProperty("os.name"),
                cityDbPath
        );
    }

    /**
     * 关闭 GeoLite2 reader。
     */
    @PreDestroy
    public void destroy() {
        if (databaseReader != null) {
            try {
                databaseReader.close();
            } catch (IOException e) {
                log.warn("Close GeoLite2 database reader failed", e);
            }
        }
    }

    /**
     * 获取客户端 IP。
     */
    public static String getIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }

        for (String header : IP_HEADERS) {
            String value = request.getHeader(header);

            if (!StringUtils.hasText(value) || "unknown".equalsIgnoreCase(value)) {
                continue;
            }

            String[] ips = value.split(",");
            for (String item : ips) {
                String ip = item.trim();

                if (isValidIp(ip)) {
                    return normalizeLoopbackIp(ip);
                }
            }
        }

        String remoteIp = request.getRemoteAddr();

        if (isValidIp(remoteIp)) {
            return normalizeLoopbackIp(remoteIp);
        }

        log.warn("Cannot get client ip");
        return "unknown";
    }

    /**
     * 判断是否是 IPv4。
     */
    public static boolean isIPv4(String ip) {
        return StringUtils.hasText(ip) && IPV4_PATTERN.matcher(ip.trim()).matches();
    }

    /**
     * 判断是否是 IPv6。
     */
    public static boolean isIPv6(String ip) {
        return StringUtils.hasText(ip) && IPV6_PATTERN.matcher(ip.trim()).matches();
    }

    /**
     * 判断是否是有效 IP。
     */
    public static boolean isValidIp(String ip) {
        return isIPv4(ip) || isIPv6(ip);
    }

    /**
     * 根据 IP 获取地址信息。
     */
    public static String getCountryCode(String ip) {
        // 业务归属地必须来自真实地址，内网不替换成演示公网IP，也不进行网络DNS查询。
        if (ip == null || !ip.matches("[0-9a-fA-F:.]+")) return "";
        try {
            InetAddress address=InetAddress.getByName(ip);
            if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress())return "";
            initDatabaseReader();
            if(databaseReader==null)return "";
            var country=databaseReader.city(address).country();
            return country==null || country.isoCode()==null ? "" : country.isoCode();
        } catch(Exception ignored){return "";}
    }

    public static Location getIpAddr(String ip, String lang) {
        String finalLang = normalizeIpLanguage(lang);

        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            return Location.other();
        }

        try {
            initDatabaseReader();

            if (databaseReader == null) {
                return Location.other();
            }

            String finalIp = normalizeLoopbackIp(ip);
            if (LOCAL_IP.equals(finalIp)) {
                finalIp = StringUtils.hasText(localTestIp) ? localTestIp : "104.21.54.158";
            }

            InetAddress ipAddress = InetAddress.getByName(finalIp);
            CityResponse response = databaseReader.city(ipAddress);

            String address = buildAddress(response, finalLang);

            return new Location(StringUtils.hasText(address) ? address : "Other");
        } catch (Exception e) {
            log.warn("Get ip address failed. ip: {}, lang: {}", ip, finalLang, e);
            return Location.other();
        }
    }

    /**
     * 获取客户端设备系统信息。
     */
    public static String clientOs(HttpServletRequest request) {
        if (request == null) {
            return "Other";
        }

        String userAgent = request.getHeader("User-Agent");

        if (!StringUtils.hasText(userAgent)) {
            return "Other";
        }

        try {
            Client client = UA_PARSER.parse(userAgent);

            String osFamily = client.os.family != null ? client.os.family : "Other";
            String osVersion = buildVersion(
                    client.os.major,
                    client.os.minor,
                    client.os.patch,
                    client.os.patchMinor
            );

            String deviceFamily = client.device.family != null ? client.device.family : "Other";
            String deviceType = resolveDeviceType(osFamily);

            StringBuilder builder = new StringBuilder(deviceType);

            if (!"Other".equalsIgnoreCase(deviceFamily)) {
                builder.append("/").append(deviceFamily);
            }

            if (!"Other".equalsIgnoreCase(osFamily)) {
                builder.append("/").append(osFamily);
            }

            if (StringUtils.hasText(osVersion)) {
                builder.append(" ").append(osVersion);
            }

            return builder.toString();
        } catch (Exception e) {
            log.warn("Parse user agent failed. userAgent: {}", userAgent, e);
            return "Other";
        }
    }

    /**
     * 初始化 GeoLite2 reader。
     */
    private static void initDatabaseReader() {
        if (databaseReader != null) {
            return;
        }

        synchronized (IpUtils.class) {
            if (databaseReader != null) {
                return;
            }

            if (!StringUtils.hasText(cityDbPath)) {
                cityDbPath = resolveCityDbPath(
                        null,
                        DEFAULT_WINDOWS_CITY_DB_PATH,
                        DEFAULT_LINUX_CITY_DB_PATH
                );
            }

            if (!StringUtils.hasText(cityDbPath)) {
                log.warn("GeoLite2 city db path is empty");
                return;
            }

            File database = new File(cityDbPath);

            if (!database.exists() || !database.isFile()) {
                log.warn(
                        "GeoLite2-City.mmdb not found. osName: {}, path: {}",
                        System.getProperty("os.name"),
                        cityDbPath
                );
                return;
            }

            try {
                databaseReader = new DatabaseReader.Builder(database).build();
            } catch (IOException e) {
                log.warn("Create GeoLite2 database reader failed. path: {}", cityDbPath, e);
            }
        }
    }

    /**
     * 根据系统自动解析 GeoLite2-City.mmdb 路径。
     *
     * 优先级：
     * 1. application.yml / application.properties 中配置的 exp.geoip.city-db-path
     * 2. Windows 使用 exp.geoip.city-db-windows-path
     * 3. Linux 使用 exp.geoip.city-db-linux-path
     * 4. 其他系统默认使用 Linux 路径
     */
    private static String resolveCityDbPath(String explicitPath, String windowsPath, String linuxPath) {
        if (StringUtils.hasText(explicitPath)) {
            return explicitPath.trim();
        }

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (osName.contains("win")) {
            return StringUtils.hasText(windowsPath)
                    ? windowsPath.trim()
                    : DEFAULT_WINDOWS_CITY_DB_PATH;
        }

        if (osName.contains("linux")) {
            return StringUtils.hasText(linuxPath)
                    ? linuxPath.trim()
                    : DEFAULT_LINUX_CITY_DB_PATH;
        }

        log.warn("Unknown operating system: {}, use linux geoip db path as default", osName);

        return StringUtils.hasText(linuxPath)
                ? linuxPath.trim()
                : DEFAULT_LINUX_CITY_DB_PATH;
    }

    /**
     * 处理本地回环地址。
     */
    private static String normalizeLoopbackIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            return "unknown";
        }

        String value = ip.trim();

        if (IPV6_LOOPBACK.equals(value) || IPV6_LOOPBACK_FULL.equals(value)) {
            return LOCAL_IP;
        }

        return value;
    }

    /**
     * 构建地址字符串。
     *
     * GeoIP2 5.x 开始：
     * getCountry() / getCity() / getLocation() / getPostal() / getNames()
     * 都已经废弃，改用 record 风格方法。
     */
    private static String buildAddress(CityResponse response, String lang) {
        if (response == null) {
            return "Other";
        }

        StringBuilder address = new StringBuilder();

        com.maxmind.geoip2.record.Country countryRecord = response.country();
        String country = countryRecord == null ? null : countryRecord.names().get(lang);

        if (StringUtils.hasText(country)) {
            address.append(country);
        }

        com.maxmind.geoip2.record.Subdivision subdivisionRecord = response.mostSpecificSubdivision();
        String subdivision = subdivisionRecord == null ? null : subdivisionRecord.names().get(lang);

        if (StringUtils.hasText(subdivision)) {
            appendAddress(address, subdivision, ",");
        }

        com.maxmind.geoip2.record.City cityRecord = response.city();
        String city = cityRecord == null ? null : cityRecord.names().get(lang);

        if (StringUtils.hasText(city)) {
            appendAddress(address, city, ",");
        }

        com.maxmind.geoip2.record.Postal postalRecord = response.postal();
        String postal = postalRecord == null ? null : postalRecord.code();

        if (StringUtils.hasText(postal)) {
            appendAddress(address, postal, "/");
        }

        return address.length() == 0 ? "Other" : address.toString();
    }

    /**
     * 拼接地址。
     */
    private static void appendAddress(StringBuilder address, String value, String separator) {
        if (address.length() > 0) {
            address.append(separator);
        }

        address.append(value);
    }

    /**
     * 判断设备类型。
     */
    private static String resolveDeviceType(String osFamily) {
        if (!StringUtils.hasText(osFamily)) {
            return "Other";
        }

        String os = osFamily.toLowerCase(Locale.ROOT);

        if (os.contains("android")) {
            return "Android";
        }

        if (os.contains("ios") || os.contains("iphone") || os.contains("ipad")) {
            return "Ios";
        }

        if (os.contains("windows") || os.contains("linux") || os.contains("mac")) {
            return "PC";
        }

        return "Other";
    }

    /**
     * 构建系统版本。
     */
    private static String buildVersion(String major, String minor, String patch, String patchMinor) {
        StringBuilder version = new StringBuilder();

        if (StringUtils.hasText(major)) {
            version.append(major);

            if (StringUtils.hasText(minor)) {
                version.append(".").append(minor);

                if (StringUtils.hasText(patch)) {
                    version.append(".").append(patch);

                    if (StringUtils.hasText(patchMinor)) {
                        version.append(".").append(patchMinor);
                    }
                }
            }
        }

        return version.toString();
    }

    /**
     * 规范化 GeoLite2 语言。
     */
    private static String normalizeIpLanguage(String lang) {
        if (!StringUtils.hasText(lang)) {
            return "en";
        }

        String value = lang.trim().replace("_", "-");

        if (ALLOWED_IP_LANGUAGES.contains(value)) {
            return value;
        }

        String primaryLang = value.split("-")[0];

        if (ALLOWED_IP_LANGUAGES.contains(primaryLang)) {
            return primaryLang;
        }

        if ("zh".equalsIgnoreCase(primaryLang)) {
            return "zh-CN";
        }

        if ("pt".equalsIgnoreCase(primaryLang)) {
            return "pt-BR";
        }

        return "en";
    }

}
