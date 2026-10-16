package org.token.model.response;

/** 服务端按后台所选时区生成的展示时间。 */
public record SystemTimeView(
        String timeZone,
        String dateTimeFormat,
        String serverTime,
        long epochMillis,
        String systemTimeZone
) {
    public SystemTimeView(String timeZone, String dateTimeFormat, String serverTime, long epochMillis) {
        this(timeZone, dateTimeFormat, serverTime, epochMillis, timeZone);
    }
}
