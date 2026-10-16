package org.token.service.compute;

import org.token.repository.compute.ComputeWithdrawalLimitPolicyRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 用户提款次数独立于免费次数；提交方在会员锁内复核，配置与计数均直接查库。 */
public final class ComputeWithdrawalLimitPolicy {
    public static final String SETTING_KEY = "withdrawalFrequency";
    private final ComputeData data;
    private final ComputeWithdrawalLimitPolicyRepository repository;
    public ComputeWithdrawalLimitPolicy(ComputeData data) { this.data = data;
        this.repository = new ComputeWithdrawalLimitPolicyRepository(data);
    }

    public Map<String,Object> configuration() {
        String stored = data.setting(SETTING_KEY, "");
        return stored == null || stored.isBlank() ? fields("enabled",true,"period","DAY","count",1)
                : normalizeGlobal(data.jsonMap(stored));
    }

    public Map<String,Object> save(Map<String,Object> request) {
        Map<String,Object> config = normalizeGlobal(request);
        repository.saveSettingWrite(SETTING_KEY, data.json(config), now());
        return config;
    }

    public static Map<String,Object> normalizeGlobal(Map<String,Object> input) {
        if (!input.keySet().equals(Set.of("enabled","period","count")) || !(input.get("enabled") instanceof Boolean)
                || !Set.of("DAY","WEEK").contains(text(input.get("period")))) throw new BusinessException("server.compute.invalidInput");
        return fields("enabled",input.get("enabled"),"period",input.get("period"),"count",positiveCount(input.get("count")));
    }

    public static void ruleValues(Map<String,Object> request,Map<String,Object> previous,Map<String,Object> values) {
        Map<String,Object> prior = previous == null ? Map.of() : previous;
        String mode = text(request.getOrDefault("withdrawalLimitMode",prior.getOrDefault("withdrawalLimitMode","INHERIT")));
        if (!Set.of("INHERIT","UNLIMITED","DAY","WEEK").contains(mode)) throw new BusinessException("server.compute.invalidInput");
        int count = positiveCount(request.getOrDefault("withdrawalLimitCount",prior.getOrDefault("withdrawalLimitCount",1)));
        values.put("withdrawal_limit_mode",mode); values.put("withdrawal_limit_count",count);
    }

    private static int positiveCount(Object raw) {
        try {
            int count = new BigDecimal(text(raw)).intValueExact();
            if (count < 1 || count > 1_000_000) throw new ArithmeticException();
            return count;
        } catch (NumberFormatException | ArithmeticException ex) { throw new BusinessException("server.compute.invalidInput"); }
    }

    public Map<String,Object> assertAllowed(long memberId,Map<String,Object> rule,ZoneId zone,Instant instant) {
        Map<String,Object> global = configuration();
        String mode = text(rule.getOrDefault("withdrawalLimitMode","INHERIT"));
        if (!Set.of("INHERIT","UNLIMITED","DAY","WEEK").contains(mode)) throw new BusinessException("server.compute.invalidInput");
        boolean inherited = "INHERIT".equals(mode);
        boolean limited = inherited ? enabled(global.get("enabled")) : !"UNLIMITED".equals(mode);
        Map<String,Object> result = fields("enabled",limited,"source",inherited ? "GLOBAL" : "RULE");
        if (!limited) return result;
        String period = inherited ? text(global.get("period")) : mode;
        int limit = positiveCount(inherited ? global.get("count") : rule.get("withdrawalLimitCount"));
        LocalDate day = instant.atZone(zone).toLocalDate();
        LocalDate start = "WEEK".equals(period) ? day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : day;
        LocalDate end = "WEEK".equals(period) ? start.plusWeeks(1) : start.plusDays(1);
        Instant startAt = start.atStartOfDay(zone).toInstant(), endAt = end.atStartOfDay(zone).toInstant();
        // 以用户为单位跨币网计数，处理中占名额，明确拒绝且退冻的订单释放名额；同请求重试在外层幂等分支返回。
        Long count = repository.assertAllowedWithdrawalCount(memberId, LocalDateTime.ofInstant(startAt,ZoneOffset.UTC), LocalDateTime.ofInstant(endAt,ZoneOffset.UTC));
        long used = count == null ? 0 : count;
        if (used >= limit) throw new BusinessException("server.compute.v17.withdrawalLimitReached");
        result.putAll(fields("period",period,"count",limit,"used",used,"remaining",Math.max(0,limit-used),"resetsAt",endAt.toString()));
        return result;
    }
}
