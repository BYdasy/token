package org.token.service.compute;

import org.token.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 展示窗口的运行数据，不参与收益、钱包或业务报表；同一配置及时间窗的结果一致。 */
public final class ComputeRuntimeProjection {
    private static final BigDecimal MAX_VALUE = new BigDecimal("1000000000000");
    private ComputeRuntimeProjection() { }

    public static Map<String,Object> defaults() {
        return fields("enabled", false, "mode", "AUTO", "totalExaflops", "5.0", "exaflops", "4.30",
                "exaflopsMin", "4.15", "exaflopsMax", "4.50", "onlineGpus", 15000L,
                "onlineGpusMin", 10900L, "onlineGpusMax", 20000L, "changeSeconds", 2L, "refreshSeconds", 60L);
    }

    public static Map<String,Object> complete(Map<String,Object> input) {
        Map<String,Object> result = defaults();
        // 旧版 Token 容量、节点及运行池没有 ExaFLOPs 物理含义，不将它们换算或继续公开。
        for (String key : result.keySet()) if (input.containsKey(key)) result.put(key, input.get(key));
        return result;
    }

    public static void validate(Map<String,Object> runtime, Map<String,Object> input) {
        Map<String,Object> config = complete(input);
        String mode = text(config.get("mode"));
        if (!Set.of("AUTO", "MANUAL").contains(mode)) throw invalid();
        runtime.put("mode", mode);
        for (String key : List.of("totalExaflops", "exaflopsMin", "exaflopsMax"))
            runtime.put(key, bounded(config.get(key), 2).toPlainString());
        for (String key : List.of("onlineGpusMin", "onlineGpusMax"))
            runtime.put(key, bounded(config.get(key), 0).longValueExact());
        if (decimal(runtime.get("exaflopsMin")).compareTo(decimal(runtime.get("exaflopsMax"))) > 0
                || ("AUTO".equals(mode) && decimal(runtime.get("exaflopsMax")).compareTo(decimal(runtime.get("totalExaflops"))) > 0)
                || number(runtime.get("onlineGpusMin")) > number(runtime.get("onlineGpusMax"))) throw invalid();
        runtime.put("changeSeconds", seconds(config.get("changeSeconds"), 1, 300));
        runtime.put("refreshSeconds", seconds(config.get("refreshSeconds"), 10, 300));
        if ("AUTO".equals(mode)) {
            // 自动模式的手动字段不可作为当前展示值回写，更改范围后在新范围内重新计算。
            Map<String,Object> projection = new LinkedHashMap<>(runtime);
            projection.put("enabled", true);
            Map<String,Object> current = project(projection, Instant.now());
            runtime.put("exaflops", current.get("exaflops"));
            runtime.put("onlineGpus", current.get("onlineGpus"));
        } else {
            BigDecimal exaflops = bounded(config.get("exaflops"), 2);
            if (exaflops.compareTo(decimal(runtime.get("totalExaflops"))) > 0) throw invalid();
            runtime.put("exaflops", exaflops.toPlainString());
            runtime.put("onlineGpus", bounded(config.get("onlineGpus"), 0).longValueExact());
        }
    }

    public static Map<String,Object> project(Map<String,Object> input, Instant current) {
        Map<String,Object> runtime = complete(input);
        long interval = Math.max(1, Math.min(300, number(runtime.get("changeSeconds"))));
        long window = Math.floorDiv(current.getEpochSecond(), interval) * interval;
        boolean automatic = "AUTO".equals(runtime.get("mode")) && enabled(runtime.get("enabled"));
        Map<String,Object> first = sample(runtime, window, interval, automatic);
        for (String key : List.of("exaflops", "onlineGpus")) runtime.put(key, first.get(key));
        List<Map<String,Object>> samples = new ArrayList<>();
        if (automatic) {
            // 配置同步仍为10～300秒；一次返回覆盖下次同步及网络余量的样本，浏览器无需高频请求。
            long refresh = Math.max(10, Math.min(300, number(runtime.get("refreshSeconds"))));
            long frames = (refresh + 10 + interval - 1) / interval;
            samples.add(first);
            for (long index = 1; index <= frames; index++) samples.add(sample(runtime, window + index * interval, interval, true));
        }
        runtime.put("simulated", true);
        runtime.put("sampleSeconds", interval);
        runtime.put("serverTime", current.toString());
        runtime.put("samples", samples);
        runtime.put("updatedAt", first.get("at"));
        runtime.put("nextUpdateAt", Instant.ofEpochSecond(window + interval).toString());
        return runtime;
    }

    private static Map<String,Object> sample(Map<String,Object> runtime, long window, long interval, boolean automatic) {
        Object exaflops = runtime.get("exaflops"), onlineGpus = runtime.get("onlineGpus");
        if (automatic) {
            long frame = Math.floorDiv(window, interval);
            exaflops = between(runtime, "exaflopsMin", "exaflopsMax", wave(frame, .63))
                    .setScale(2, RoundingMode.HALF_UP).min(decimal(runtime.get("totalExaflops"))).toPlainString();
            onlineGpus = between(runtime, "onlineGpusMin", "onlineGpusMax", wave(frame, 1.84))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        return fields("at", Instant.ofEpochSecond(window).toString(), "exaflops", exaflops, "onlineGpus", onlineGpus);
    }

    private static double wave(long frame, double phase) {
        // 以帧而非浏览器时钟生成确定性波形；调整变化间隔后仍保持可见变化，不会与周期整倍数抵消。
        return (Math.sin(Math.floorMod(frame, 8) * (2 * Math.PI / 8) + phase) * .6
                + Math.sin(Math.floorMod(frame, 21) * (2 * Math.PI / 21) + phase) * .3
                + Math.sin(Math.floorMod(frame, 450) * (2 * Math.PI / 450)) * .1 + 1) / 2;
    }

    private static BigDecimal between(Map<String,Object> row, String minimum, String maximum, double fraction) {
        BigDecimal low = decimal(row.get(minimum)), high = decimal(row.get(maximum));
        return low.add(high.subtract(low).multiply(BigDecimal.valueOf(Math.max(0, Math.min(1, fraction)))));
    }

    private static BigDecimal bounded(Object raw, int precision) {
        try {
            BigDecimal value = new BigDecimal(String.valueOf(raw).trim());
            if (value.signum() < 0 || value.compareTo(MAX_VALUE) > 0 || value.stripTrailingZeros().scale() > precision) throw invalid();
            return value;
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    private static long seconds(Object raw, int min, int max) {
        long value = bounded(raw, 0).longValueExact();
        if (value < min || value > max) throw invalid();
        return value;
    }

    private static BusinessException invalid() { return new BusinessException("server.compute.ui.runtimeMetricsInvalid"); }
}
