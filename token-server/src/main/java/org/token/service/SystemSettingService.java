package org.token.service;

import org.token.model.request.system.SystemSettingsUpdateRequest;
import org.token.model.response.SystemSettingsView;
import org.token.model.response.SystemTimeView;

public interface SystemSettingService {
    SystemSettingsView getSettings();
    SystemSettingsView getPublicSettings();
    SystemTimeView getServerTime();
    SystemTimeView getServerTime(String accountTimeZone);
    String resolveDisplayTimeZone(String accountTimeZone);
    /** 同一次请求已读到系统时区时复用该值；展示配置仍实时读取，不跨请求缓存。 */
    default String resolveDisplayTimeZoneUsingSystem(String systemTimeZone) { return resolveDisplayTimeZone(null); }
    /** 业务自然日、报表筛选和定时任务统一使用系统时区。 */
    default String resolveSystemTimeZone() { return resolveDisplayTimeZone(null); }
    SystemSettingsView updateSettings(SystemSettingsUpdateRequest request, Integer operatorUid);
    String get(String key, String fallback);
    boolean getBoolean(String key, boolean fallback);
    void updateValue(String key, String value, Integer operatorUid);
    void invalidateCache();
}
