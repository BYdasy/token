package org.token.tools.time;

import java.time.ZoneId;
import java.time.ZoneOffset;

/** 当前 HTTP 请求的展示时区以及 JSON 请求/响应转换阶段。 */
public final class RequestTimeZoneContext {

    private static final ThreadLocal<State> HOLDER = new ThreadLocal<>();

    private RequestTimeZoneContext() {
    }

    public static void setZone(ZoneId zoneId) {
        State state = state();
        state.zoneId = zoneId == null ? ZoneOffset.UTC : zoneId;
    }

    public static ZoneId zone() {
        State state = HOLDER.get();
        return state == null || state.zoneId == null ? ZoneOffset.UTC : state.zoneId;
    }

    public static void setResponseZone(ZoneId zoneId) { state().responseZone = zoneId; }
    public static ZoneId responseZone() { return state().responseZone == null ? zone() : state().responseZone; }
    public static void setUserApi(boolean value) { state().userApi = value; }
    public static boolean isUserApi() { return state().userApi; }

    public static void beginRequestBody() {
        state().requestBody = true;
    }

    public static void endRequestBody() {
        State state = HOLDER.get();
        if (state != null) {
            state.requestBody = false;
        }
    }

    public static boolean isRequestBody() {
        State state = HOLDER.get();
        return state != null && state.requestBody;
    }

    public static void beginResponseBody() {
        state().responseBody = true;
    }

    public static boolean isResponseBody() {
        State state = HOLDER.get();
        return state != null && state.responseBody;
    }

    public static void clear() {
        HOLDER.remove();
    }

    private static State state() {
        State state = HOLDER.get();
        if (state == null) {
            state = new State();
            state.zoneId = ZoneOffset.UTC;
            HOLDER.set(state);
        }
        return state;
    }

    private static final class State {
        private ZoneId zoneId;
        private ZoneId responseZone;
        private boolean userApi;
        private boolean requestBody;
        private boolean responseBody;
    }
}
