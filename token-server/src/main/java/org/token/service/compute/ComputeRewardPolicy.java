package org.token.service.compute;

import org.token.repository.compute.ComputeRewardRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 奖励表单与发奖共用规则校验；新版来源使用已入账USD，不在奖励引擎重取汇率。 */
public final class ComputeRewardPolicy {
    private ComputeRewardPolicy() {}

    /** 启用任一 VIP 充值返点时接管旧充值引擎；关闭返点不会恢复旧规则。调用方已持有关系锁和事务。 */
    public static void activateDepositEngine(ComputeData data) {
        ComputeRewardRepository repository=new ComputeRewardRepository(data);
        Map<String,Object> current=repository.configurationRewardConfigurationRow();
        if(current.get("activatedAt")!=null)return;
        String document=data.json(fields("configuration",data.jsonMap(current.get("configurationJson")),
                "activatedAt",java.time.Instant.now().toString()));
        if(repository.saveRewardConfigurationWrite(document,now(),current.get("updatedBy"),current.get("revision"))!=1)
            throw new BusinessException("server.compute.reward.configChanged");
    }

    public static List<Map<String,Object>> normalizeVipRules(Object raw,int teamDepth) {
        List<Map<String,Object>> result=new ArrayList<>();Set<Integer> depths=new HashSet<>();
        for(Map<String,Object> row:maps(raw,200)) {
            int depth=integer(row.get("depth"),1,Math.min(200,Math.max(0,teamDepth)));
            if(!depths.add(depth))throw invalid();
            Map<String,Object> item=fields("depth",depth);
            for(String kind:List.of("firstDeposit","deposit","vip","product")) {
                item.put(kind+"Enabled",enabled(row.get(kind+"Enabled")));
                item.put(kind+"Rate",value(row.get(kind+"Rate"),true).toPlainString());
            }
            // 缺省关闭新增条件，保留旧分层规则的实际资金语义。
            for(String condition:List.of("depositCapByCompute","vipRequireComputeBalance","requireEffectiveMember"))
                item.put(condition,enabled(row.get(condition)));
            item.put("effectiveDepositThreshold",value(row.get("effectiveDepositThreshold"),false).toPlainString());
            result.add(item);
        }
        result.sort(Comparator.comparingLong(row->number(row.get("depth"))));return result;
    }

    public static List<Map<String,Object>> readVipRules(ComputeData data,Object raw) {
        if(raw==null||text(raw).isBlank())return List.of();
        if(raw instanceof List<?>)return maps(raw,200);
        String json=raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):text(raw);
        return maps(data.jsonMap("{\"rules\":"+json+"}").get("rules"),200);
    }

    public static Map<String,Object> normalizeConfiguration(Map<String,Object> raw) {
        List<Map<String,Object>> deposits=new ArrayList<>(),effective=new ArrayList<>();
        Set<String> currencies=new HashSet<>();
        for(Map<String,Object> rule:maps(raw.get("depositRules"),32)) {
            String currency=currency(rule.get("currency"));if(!currencies.add(currency))throw invalid();
            String participation=choice(rule.get("participation"),"FIRST","ALL");
            List<Map<String,Object>> tiers=new ArrayList<>();Set<BigDecimal> thresholds=new HashSet<>();
            for(Map<String,Object> tier:maps(rule.get("tiers"),50)) {
                BigDecimal threshold=amount(tier.get("threshold")).stripTrailingZeros();if(!thresholds.add(threshold))throw invalid();
                Map<String,Object> item=fields("threshold",threshold.toPlainString());
                for(String target:List.of("self","direct")) {
                    String mode=choice(tier.get(target+"Mode"),"FIXED","PERCENT");
                    item.put(target+"Mode",mode);item.put(target+"Value",value(tier.get(target+"Value"),"PERCENT".equals(mode)).toPlainString());
                }
                tiers.add(item);
            }
            tiers.sort(Comparator.comparing(row->decimal(row.get("threshold"))));
            if(enabled(rule.get("enabled"))&&tiers.isEmpty())throw invalid();
            deposits.add(fields("currency",currency,"enabled",enabled(rule.get("enabled")),"participation",participation,"tiers",tiers));
        }
        currencies.clear();
        for(Map<String,Object> rule:maps(raw.get("effectiveRules"),32)) {
            String currency=currency(rule.get("currency"));if(!currencies.add(currency))throw invalid();
            List<Map<String,Object>> tiers=new ArrayList<>();Set<Integer> counts=new HashSet<>();
            for(Map<String,Object> tier:maps(rule.get("tiers"),50)) {
                int count=integer(tier.get("count"),1,1000000);if(!counts.add(count))throw invalid();
                tiers.add(fields("count",count,"amount",amount(tier.get("amount")).toPlainString()));
            }
            tiers.sort(Comparator.comparingLong(row->number(row.get("count"))));
            if(enabled(rule.get("enabled"))&&tiers.isEmpty())throw invalid();
            effective.add(fields("currency",currency,"enabled",enabled(rule.get("enabled")),"basis",choice(rule.get("basis"),"FIRST","CUMULATIVE"),
                    "threshold",amount(rule.get("threshold")).toPlainString(),"tiers",tiers));
        }
        return fields("depositRules",deposits,"effectiveRules",effective);
    }

    public static BigDecimal reward(BigDecimal source,Object mode,Object configured) {
        BigDecimal value=decimal(configured);
        return ("PERCENT".equals(mode)?source.multiply(value).divide(BigDecimal.valueOf(100),8,RoundingMode.DOWN):value).setScale(8,RoundingMode.DOWN);
    }
    public static List<Map<String,Object>> maps(Object value,int maximum) {
        if(value==null)return List.of();if(!(value instanceof List<?> list)||list.size()>maximum)throw invalid();
        List<Map<String,Object>> result=new ArrayList<>();
        for(Object row:list) {
            if(!(row instanceof Map<?,?> map))throw invalid();
            Map<String,Object> item=new LinkedHashMap<>();map.forEach((key,entry)->item.put(String.valueOf(key),entry));result.add(item);
        }
        return result;
    }
    private static BigDecimal value(Object raw,boolean percent) {
        BigDecimal value=decimal(raw);
        if(value.signum()<0||value.scale()>8||value.precision()-value.scale()>18||(percent&&value.compareTo(BigDecimal.valueOf(100))>0))throw invalid();
        return value;
    }
    private static String currency(Object raw) {String result=text(raw).toUpperCase(Locale.ROOT);if(!Set.of("USD","USDT","USDC","USDT0").contains(result))throw invalid();return ComputeUsdAccounting.WALLET_CURRENCY;}
    private static String choice(Object raw,String... values) {String value=text(raw);if(!Set.of(values).contains(value))throw invalid();return value;}
    private static int integer(Object raw,int min,int max) {long value=number(raw);if(value<min||value>max)throw invalid();return (int)value;}
    private static BusinessException invalid(){return new BusinessException("server.compute.reward.invalidRules");}
}
