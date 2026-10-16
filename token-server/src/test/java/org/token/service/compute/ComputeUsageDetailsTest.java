package org.token.service.compute;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputeUsageDetailsTest {
    @Test void generatedUsageIsStableBoundedAndConserved() {
        for (String balance : List.of("0","0.00000001","100","999999999999999999")) {
            Map<String,Object> plan=fields("tokenBasis",balance,"models",List.of(fields("name","模型甲"),fields("name","模型乙"),fields("name","模型丙")));
            Map<String,Object> result=ComputeUsageDetails.generate(plan,98172);
            assertEquals(result,ComputeUsageDetails.generate(plan,98172));
            long total=number(result.get("tokenUsage"));
            assertTrue(total>0 && total<=5_000_000);
            assertEquals(total,number(result.get("inputTokens"))+number(result.get("outputTokens")));
            long sum=0;
            BigDecimal percent=BigDecimal.ZERO;
            for(Object raw:(List<?>)result.get("models")) {
                Map<?,?> model=(Map<?,?>)raw;
                long count=number(model.get("tokenUsage"));
                assertEquals(count,number(model.get("inputTokens"))+number(model.get("outputTokens")));
                assertTrue(count<=number(model.get("modelCalls"))*2048);
                sum+=count; percent=percent.add(decimal(model.get("allocationPercent")));
            }
            assertEquals(total,sum); assertEquals(0,BigDecimal.valueOf(100).compareTo(percent));
        }
    }
    @Test void noModelsDoesNotInventUsage() {
        assertEquals(0,number(ComputeUsageDetails.generate(fields("tokenBasis","100","models",List.of()),1).get("tokenUsage")));
    }

    @Test void differentOrdersVaryModelSharesWithoutChangingTheSnapshot() {
        List<Map<String,Object>> models=List.of(fields("name","模型甲","allocationPercent",50),fields("name","模型乙","allocationPercent",50));
        Map<String,Object> plan=fields("tokenBasis","5000","usageSeed",99,"models",models);
        Set<Object> shares=new HashSet<>();
        for (long seed=1;seed<=20;seed++) {
            Map<String,Object> result=ComputeUsageDetails.generate(plan,seed);
            shares.add(((Map<?,?>)((List<?>)result.get("models")).get(0)).get("allocationPercent"));
            assertFalse(result.containsKey("usageSeed"));
        }
        assertTrue(shares.size()>10,"不同订单的模型用量占比应变化，不再固定平均分配");
        assertEquals(50,models.get(0).get("allocationPercent"));
        assertFalse(models.get(0).containsKey("tokenUsage"));
        assertEquals(99,plan.get("usageSeed"));
    }

    @Test void smallBatchesAndMaximumModelCountKeepTokensAndPercentagesConserved() {
        List<Map<String,Object>> models=new ArrayList<>();
        for (int index=0;index<50;index++) models.add(fields("name","模型"+index));
        for (long seed=0;seed<100;seed++) {
            Map<String,Object> result=ComputeUsageDetails.generate(fields("tokenBasis","0","models",models),seed);
            long total=number(result.get("tokenUsage")),sum=0;
            BigDecimal percentage=BigDecimal.ZERO;
            for (Object raw:(List<?>)result.get("models")) {
                Map<?,?> model=(Map<?,?>)raw;
                long count=number(model.get("tokenUsage"));
                assertTrue(count>=0);
                assertEquals(count,number(model.get("inputTokens"))+number(model.get("outputTokens")));
                if (count==0) assertEquals(0,number(model.get("modelCalls")));
                sum+=count;
                percentage=percentage.add(decimal(model.get("allocationPercent")));
            }
            assertEquals(total,sum);
            assertEquals(0,BigDecimal.valueOf(100).compareTo(percentage));
        }
    }

    @Test void singleModelReceivesAllUsage() {
        Map<String,Object> result=ComputeUsageDetails.generate(fields("tokenBasis","100","models",List.of(fields("name","模型甲"))),7);
        Map<?,?> model=(Map<?,?>)((List<?>)result.get("models")).get(0);
        assertEquals(result.get("tokenUsage"),model.get("tokenUsage"));
        assertEquals(0,BigDecimal.valueOf(100).compareTo(decimal(model.get("allocationPercent"))));
    }

    @Test void threeMainModelsAndOtherRemainRandomStableAndConserved() {
        Set<Object> selectedModels = new HashSet<>();
        Set<Object> mainShares = new HashSet<>();
        for (int count : List.of(4, 5, 50, 150)) {
            List<Map<String,Object>> candidates = new ArrayList<>();
            for (int index = 0; index < count; index++) candidates.add(fields("name", "模型" + index, "modelId", index + 1));
            for (long seed = 0; seed < 100; seed++) {
                Map<String,Object> plan = fields("allocationPolicy", ComputeUsageDetails.TOP_THREE_OTHER,
                        "tokenBasis", seed % 2 == 0 ? "0" : "100000", "models", candidates);
                Map<String,Object> result = ComputeUsageDetails.generate(plan, seed);
                assertEquals(result, ComputeUsageDetails.generate(plan, seed));
                List<?> visible = (List<?>) result.get("models"), details = (List<?>) result.get("modelAllocations");
                assertEquals(4, visible.size()); assertEquals(count, details.size());
                assertEquals(candidates.size(), new HashSet<>(details.stream().map(raw -> ((Map<?,?>) raw).get("modelId")).toList()).size());
                BigDecimal major = BigDecimal.ZERO, percent = BigDecimal.ZERO;
                long tokens = 0, inputs = 0, outputs = 0, calls = 0;
                for (int index = 0; index < visible.size(); index++) {
                    Map<?,?> model = (Map<?,?>) visible.get(index);
                    BigDecimal share = decimal(model.get("allocationPercent"));
                    if (index < 3) {
                        assertFalse(model.containsKey("otherModels")); major = major.add(share);
                        selectedModels.add(model.get("modelId"));
                    } else {
                        assertEquals(true, model.get("otherModels")); assertEquals(count - 3, model.get("modelCount"));
                    }
                    percent = percent.add(share); tokens += number(model.get("tokenUsage"));
                    inputs += number(model.get("inputTokens")); outputs += number(model.get("outputTokens")); calls += number(model.get("modelCalls"));
                    assertEquals(number(model.get("tokenUsage")), number(model.get("inputTokens")) + number(model.get("outputTokens")));
                }
                assertTrue(major.compareTo(BigDecimal.valueOf(70)) >= 0 && major.compareTo(BigDecimal.valueOf(90)) <= 0, major.toPlainString());
                mainShares.add(major);
                assertEquals(0, percent.compareTo(BigDecimal.valueOf(100)));
                assertEquals(number(result.get("tokenUsage")), tokens);
                assertEquals(number(result.get("inputTokens")), inputs);
                assertEquals(number(result.get("outputTokens")), outputs);
                assertEquals(number(result.get("modelCalls")), calls);
                assertFalse(candidates.get(0).containsKey("tokenUsage"));
            }
        }
        assertTrue(selectedModels.size() > 3); assertTrue(mainShares.size() > 50);
    }

    @Test void smallProviderCatalogDoesNotInventAnOtherModel() {
        for (int count = 0; count <= 3; count++) {
            List<Map<String,Object>> models = new ArrayList<>();
            for (int index = 0; index < count; index++) models.add(fields("name", "模型" + index));
            Map<String,Object> result = ComputeUsageDetails.generate(fields("allocationPolicy", ComputeUsageDetails.TOP_THREE_OTHER,
                    "tokenBasis", "100", "models", models), 91);
            assertEquals(count, ((List<?>) result.get("models")).size());
            assertFalse(result.containsKey("modelAllocations"));
            assertTrue(((List<?>) result.get("models")).stream().noneMatch(raw -> ((Map<?,?>) raw).containsKey("otherModels")));
        }
    }
}
