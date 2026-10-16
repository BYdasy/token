package org.token.service.compute;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.token.repository.compute.ComputeData.*;

/** 展示用运行明细。固定种子保证重试一致；不调用钱包，也不作为供应商计费凭证。 */
public final class ComputeUsageDetails {
    public static final String TOP_THREE_OTHER = "TOP_THREE_OTHER_V1";
    private ComputeUsageDetails() { }

    public static Map<String,Object> generate(Map<String,Object> plan, long seed) {
        Map<String,Object> result = new LinkedHashMap<>(plan);
        List<Map<String,Object>> models = new ArrayList<>();
        if (plan.get("models") instanceof List<?> rows) {
            for (Object row : rows) if (row instanceof Map<?,?> map) {
                Map<String,Object> model = new LinkedHashMap<>();
                map.forEach((key,value) -> model.put(String.valueOf(key),value));
                models.add(model);
            }
        }
        Random random = new Random(seed);
        boolean grouped = TOP_THREE_OTHER.equals(plan.get("allocationPolicy"));
        if (grouped) Collections.shuffle(models, random);
        // 每次工作量限制在 256～500 万 Token；固定收益且无余额时使用最小批次。
        long budget = decimal(plan.get("tokenBasis")).multiply(BigDecimal.valueOf(1000))
                .max(BigDecimal.valueOf(256)).min(BigDecimal.valueOf(5_000_000)).setScale(0,RoundingMode.DOWN).longValue();
        long total = models.isEmpty() ? 0 : Math.max(1, budget * (60 + random.nextInt(41)) / 100);
        // 每个订单独立抽取权重，模型数量只决定候选项，不决定固定占比。
        long[] weights = new long[models.size()];
        for (int index = 0; index < weights.length; index++) weights[index] = 1 + random.nextInt(10_000);
        long[] usages = grouped && models.size() > 3 ? dominantUsages(total, models.size(), random) : apportion(total, weights);
        // 百分比由实际整数 Token 反算；按百分之一补齐尾差，显示总和保持 100%。
        long[] percentages = apportion(total == 0 ? 0 : 10_000, usages);
        if (grouped && models.size() > 3) {
            long mainTotal = usages[0] + usages[1] + usages[2];
            long mainPercent = mainTotal * 10_000 / total;
            long[] main = apportion(mainPercent, Arrays.copyOf(usages, 3));
            long[] others = apportion(10_000 - mainPercent, Arrays.copyOfRange(usages, 3, usages.length));
            System.arraycopy(main, 0, percentages, 0, 3);
            System.arraycopy(others, 0, percentages, 3, others.length);
        }
        long inputs = 0, calls = 0;
        for (int index=0; index<models.size(); index++) {
            Map<String,Object> model=models.get(index);
            BigDecimal percent=BigDecimal.valueOf(percentages[index], 2);
            long tokens=usages[index];
            long input=tokens*(65+random.nextInt(21))/100;
            long callCount=tokens==0 ? 0 : (tokens+2047)/2048;
            model.putAll(fields("allocationPercent",percent,"tokenUsage",tokens,"inputTokens",input,
                    "outputTokens",tokens-input,"modelCalls",callCount,"executionState","SUCCEEDED"));
            inputs+=input; calls+=callCount;
        }
        List<Map<String, Object>> visible = models;
        if (grouped && models.size() > 3) {
            // 完整分配随结果落库，用户只展示三项及“其他”；聚合不改变整数用量和收益。
            result.put("modelAllocations", models);
            visible = new ArrayList<>(models.subList(0, 3));
            visible.sort(Comparator.comparingLong((Map<String, Object> model) -> number(model.get("tokenUsage"))).reversed());
            Map<String, Object> other = fields("otherModels", true, "name", "", "provider", "", "modelCount", models.size() - 3,
                    "allocationPercent", BigDecimal.ZERO, "tokenUsage", 0L, "inputTokens", 0L, "outputTokens", 0L, "modelCalls", 0L,
                    "executionState", "SUCCEEDED");
            for (Map<String, Object> model : models.subList(3, models.size())) {
                other.put("allocationPercent", decimal(other.get("allocationPercent")).add(decimal(model.get("allocationPercent"))));
                for (String key : List.of("tokenUsage", "inputTokens", "outputTokens", "modelCalls"))
                    other.put(key, number(other.get(key)) + number(model.get(key)));
            }
            visible.add(other);
        }
        result.putAll(fields("source","SIMULATED_ALLOCATION","models",visible,"tokenUsage",total,
                "inputTokens",inputs,"outputTokens",total-inputs,"modelCalls",calls));
        result.remove("usageSeed");
        return result;
    }

    /** 三个主要模型占 70%～90%；只有四个模型时至少 75%，确保余下一项不会反超主要模型。 */
    private static long[] dominantUsages(long total, int count, Random random) {
        long minimum = (total * (count == 4 ? 75 : 70) + 99) / 100;
        long maximum = total * 90 / 100;
        long mainTotal = minimum + random.nextInt((int) (maximum - minimum + 1));
        long[] remainderWeights = new long[count - 3];
        // 较小的尾部权重波动避免少数模型反超主要项，仍保留每笔随机分配。
        for (int index = 0; index < remainderWeights.length; index++) remainderWeights[index] = 90 + random.nextInt(21);
        long[] remainder = apportion(total - mainTotal, remainderWeights);
        long floor = Arrays.stream(remainder).max().orElse(0);
        long[] main = apportion(mainTotal - floor * 3,
                new long[]{1 + random.nextInt(10_000), 1 + random.nextInt(10_000), 1 + random.nextInt(10_000)});
        long[] usages = new long[count];
        for (int index = 0; index < 3; index++) usages[index] = floor + main[index];
        System.arraycopy(remainder, 0, usages, 3, remainder.length);
        return usages;
    }

    /** 最大余数法补齐整数尾差，避免所有舍入余量固定落在最后一个模型。 */
    private static long[] apportion(long total, long[] weights) {
        long[] values = new long[weights.length];
        long weightSum = Arrays.stream(weights).sum();
        if (total == 0 || weightSum == 0) return values;
        long allocated = 0;
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < weights.length; index++) {
            values[index] = total * weights[index] / weightSum;
            allocated += values[index];
            order.add(index);
        }
        order.sort(Comparator.<Integer>comparingLong(index -> total * weights[index] % weightSum).reversed());
        for (int index = 0; index < total - allocated; index++) values[order.get(index)]++;
        return values;
    }
}
