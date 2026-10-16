package org.token.service.compute;

import org.token.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 展示窗口的运行数据，不参与收益、钱包或业务报表；同一配置及时间窗的结果一致。 */
public final class ComputeRuntimeProjection {
    private static final long SAMPLE_SECONDS = 2;
    private ComputeRuntimeProjection() { }
    public static Map<String,Object> defaults() {
        return fields("enabled", false, "mode", "AUTO", "totalNodes", 64, "activeNodes", 52,
                "totalPools", 8, "runningPools", 6, "capacity", "2000000", "consumed", "1400000",
                "activeMinPercent", 75, "activeMaxPercent", 94, "usageMinPercent", 55, "usageMaxPercent", 82, "refreshSeconds", 60);
    }
    public static Map<String,Object> complete(Map<String,Object> input) {
        Map<String,Object> result = defaults();
        result.putAll(input);
        return result;
    }
    public static void validate(Map<String,Object> runtime, Map<String,Object> input) {
        Map<String,Object> config = complete(input);
        String mode = text(config.get("mode"));
        if (!Set.of("AUTO", "MANUAL").contains(mode)) throw invalid();
        runtime.put("mode", mode);
        for (String key : List.of("activeMinPercent", "activeMaxPercent", "usageMinPercent", "usageMaxPercent")) {
            BigDecimal value = decimal(config.get(key));
            if (value.scale() > 2 || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid();
            runtime.put(key, value);
        }
        if (decimal(runtime.get("activeMinPercent")).compareTo(decimal(runtime.get("activeMaxPercent"))) > 0
                || decimal(runtime.get("usageMinPercent")).compareTo(decimal(runtime.get("usageMaxPercent"))) > 0) throw invalid();
        long seconds = number(config.get("refreshSeconds"));
        if (seconds < 10 || seconds > 300) throw invalid();
        runtime.put("refreshSeconds", seconds);
    }
    public static Map<String,Object> project(Map<String,Object> input, Instant current) {
        Map<String,Object> runtime = complete(input);
        long window = Math.floorDiv(current.getEpochSecond(), SAMPLE_SECONDS) * SAMPLE_SECONDS;
        boolean automatic = "AUTO".equals(runtime.get("mode")) && enabled(runtime.get("enabled"));
        Map<String,Object> first = sample(runtime, window, automatic);
        for (String key : List.of("activeNodes", "runningPools", "consumed", "utilizationPercent")) runtime.put(key, first.get(key));
        List<Map<String,Object>> samples = new ArrayList<>();
        if (automatic) {
            // 配置同步仍使用10～300秒窗口；一次返回覆盖下次同步及网络余量的2秒样本，浏览器无需高频请求。
            long refresh = Math.max(10, Math.min(300, number(runtime.get("refreshSeconds"))));
            long frames = (refresh + 10 + SAMPLE_SECONDS - 1) / SAMPLE_SECONDS;
            samples.add(first);
            for (long index = 1; index <= frames; index++) samples.add(sample(runtime, window + index * SAMPLE_SECONDS, true));
        }
        runtime.put("simulated", true);
        runtime.put("sampleSeconds", SAMPLE_SECONDS);
        runtime.put("serverTime", current.toString());
        runtime.put("samples", samples);
        runtime.put("updatedAt", first.get("at"));
        runtime.put("nextUpdateAt", Instant.ofEpochSecond(window + SAMPLE_SECONDS).toString());
        return runtime;
    }
    private static Map<String,Object> sample(Map<String,Object> runtime, long window, boolean automatic) {
        Object activeNodes = runtime.get("activeNodes"), runningPools = runtime.get("runningPools"), consumed = runtime.get("consumed");
        BigDecimal capacity = decimal(runtime.get("capacity"));
        if (automatic) {
            // 短周期提供可见变化，慢周期调整整体负载；以UTC帧计算，同配置及同帧跨实例结果一致。
            double phase = decimal(runtime.get("totalNodes")).doubleValue() * 0.017;
            double wave = Math.sin(Math.floorMod(window, 16) * (2 * Math.PI / 16) + phase) * .6
                    + Math.sin(Math.floorMod(window, 42) * (2 * Math.PI / 42)) * .3
                    + Math.sin(Math.floorMod(window, 900) * (2 * Math.PI / 900)) * .1;
            double load = (wave + 1) / 2;
            double activity = between(runtime, "activeMinPercent", "activeMaxPercent", load);
            double usage = between(runtime, "usageMinPercent", "usageMaxPercent", load);
            long nodes = number(runtime.get("totalNodes")), pools = number(runtime.get("totalPools"));
            long active = Math.max(0, Math.min(nodes, Math.round(nodes * activity / 100)));
            long running = active == 0 ? 0 : Math.min(Math.min(pools, active), Math.max(1, Math.round(pools * activity / 100)));
            activeNodes = active;
            runningPools = running;
            consumed = running == 0 ? "0" : capacity.multiply(BigDecimal.valueOf(usage))
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP).min(capacity).stripTrailingZeros().toPlainString();
        }
        String utilization = capacity.signum() == 0 ? "0" : decimal(consumed).multiply(BigDecimal.valueOf(100))
                .divide(capacity, 2, RoundingMode.HALF_UP).toPlainString();
        return fields("at", Instant.ofEpochSecond(window).toString(), "activeNodes", activeNodes,
                "runningPools", runningPools, "consumed", consumed, "utilizationPercent", utilization);
    }
    private static double between(Map<String,Object> row, String minimum, String maximum, double fraction) {
        double low = decimal(row.get(minimum)).doubleValue(), high = decimal(row.get(maximum)).doubleValue();
        return low + (high - low) * Math.max(0, Math.min(1, fraction));
    }
    private static BusinessException invalid() { return new BusinessException("server.compute.ui.runtimeInvalid"); }
}
