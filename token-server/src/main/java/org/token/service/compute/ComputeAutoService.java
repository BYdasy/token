package org.token.service.compute;

import org.token.repository.compute.ComputeAutoRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.util.identifier.ComputeBusinessNumbers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

import static org.token.repository.compute.ComputeData.*;

/** VIP 点击即时发放收益；动画与延后生成的使用明细不参与资金结算。 */
@Service
@Slf4j
public class ComputeAutoService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ComputeData data;
    private final ComputeAutoRepository repository;
    private final ComputeBusinessService business;
    private final ComputeCurrencyService currencies;
    private final ComputeVipPeriodService periods;
    private final TransactionTemplate transactions;
    private volatile long lastSchedulerWarning;

    @Autowired
    public ComputeAutoService(ComputeData data, ComputeBusinessService business, ComputeCurrencyService currencies,
                              ComputeVipPeriodService periods, PlatformTransactionManager manager, ComputeAutoRepository repository) {
        this.data = data;
        this.business = business;
        this.currencies = currencies;
        this.periods = periods;
        this.transactions = new TransactionTemplate(manager);

        this.repository = repository;
    }

    public ComputeAutoService(ComputeData data, ComputeBusinessService business, ComputeCurrencyService currencies,
                              ComputeVipPeriodService periods, PlatformTransactionManager manager) {
        this(data, business, currencies, periods, manager, new ComputeAutoRepository(data));
    }

    public ComputeAutoService(ComputeData data, ComputeBusinessService business, ComputeCurrencyService currencies) {
        this(data, business, currencies, new ComputeVipPeriodService(data, null),
                new DataSourceTransactionManager(data.jdbc().getDataSource()));
    }

    public Map<String, Object> records(long memberId, int page, int size) {
        int limit = Math.max(1, Math.min(100, size));
        return fields("records", repository.recordsAutoContractRows(memberId, limit, (long) (Math.max(1, page) - 1) * limit).stream().map(this::publicRecord).toList(),
                "total", repository.recordsAutoContractCount(memberId));
    }

    @Transactional
    public Map<String, Object> activate(long memberId, Map<String, Object> request) {
        business.lockRewards();
        Map<String, Object> member = repository.activateMemberRow(memberId);
        String requestId = text(request.get("requestId"));
        if (!requestId.matches("[A-Za-z0-9:_-]{8,80}")) throw new BusinessException("server.compute.requestIdRequired");
        List<Map<String, Object>> previous = repository.activateAutoContractRows(memberId, requestId);
        if (!previous.isEmpty()) return publicRecord(previous.get(0));
        Map<String, Object> level = repository.activateLevelRow(member.get("level"));
        if (!enabled(level.get("autoEnabled"))) throw new BusinessException("server.compute.v6.autoDisabled");
        validateClickRules(level);
        LocalDateTime starts = now();
        Map<String, Object> state = statusOf(memberId, member, level, starts);
        if (!Boolean.TRUE.equals(state.get("canExecute"))) throw new BusinessException(text(state.get("reasonKey")));
        new ComputeContentTaskGuard(data).assertSceneCompleted(memberId, "INVEST");
        String currency = currencies.defaultCurrency(), orderNo = ComputeBusinessNumbers.next("AC");
        // 同一会员的资金写入共用会员锁；此余额仅为计算基数，不扣款、不冻结。
        List<Map<String,Object>> wallets = repository.activateWalletRows(memberId, currency);
        BigDecimal principal = wallets.isEmpty() ? BigDecimal.ZERO : decimal(wallets.get(0).get("available")).max(BigDecimal.ZERO);
        BigDecimal min = decimal(level.get("autoClickRateMin"));
        BigDecimal max = decimal(level.get("autoClickRateMax"));
        BigDecimal dailyRate = min.add(max.subtract(min).multiply(BigDecimal.valueOf(RANDOM.nextInt(1_000_001)))
                .divide(new BigDecimal("1000000"), 8, RoundingMode.DOWN));
        if ("FIXED".equals(level.get("vipRewardMode"))) dailyRate = BigDecimal.ZERO;
        BigDecimal baseIncome = "FIXED".equals(level.get("vipRewardMode")) ? decimal(level.get("vipFixedReward")) : ComputeVipPolicy.income(principal, dailyRate);
        BigDecimal tokenIncomePercent = ComputeVipPolicy.tokenIncomePercent(member.get("tokenIncomePercent"));
        BigDecimal income = ComputeVipPolicy.income(baseIncome, tokenIncomePercent);
        LocalDateTime resultAt = starts.plusMinutes(number(level.get("vipDetailsDelayMinutes")));
        Map<String,Object> snapshot = new LinkedHashMap<>(level);
        // 比例与折前/折后金额在首次执行时固定，幂等重试不读取之后修改的比例。
        snapshot.put("tokenIncomePercent", tokenIncomePercent.toPlainString());
        snapshot.put("baseIncome", baseIncome.toPlainString());
        snapshot.put("settledIncome", income.toPlainString());
        snapshot.put("usagePlan", fields("source", "CONFIGURED_ALLOCATION", "models", ComputeVipModelAllocation.models(data, level),
                "allocationPolicy", ComputeUsageDetails.TOP_THREE_OTHER,
                "tokenBasis", principal.toPlainString(), "usageSeed", RANDOM.nextLong(),
                "stages", List.of("INCOME_PAID", "DETAILS_GENERATED")));
        long id = data.insert("compute_auto_contract", fields("order_no", orderNo, "member_id", memberId,
                "level_id", member.get("level"), "level_name", level.get("name"), "request_id", requestId,
                "currency", currency, "amount", principal, "daily_rate", dailyRate,
                "rule_snapshot", data.json(snapshot), "expected_income", income, "paid_income", income,
                "status", "COMPLETED", "starts_at", starts, "created_at", starts,
                "result_available_at", resultAt, "execution_finished_at", starts,
                "animation_ends_at", starts.plusSeconds(number(level.get("autoDisplaySeconds"))),
                "details_status", resultAt.isAfter(starts) ? "PENDING" : "READY",
                "execution_result", resultAt.isAfter(starts) ? null : data.json(snapshot.get("usagePlan")), "execution_time_zone", periods.zone().getId()));
        if (income.signum() > 0) business.creditVipIncome(memberId, currency, income, id, 1, orderNo);
        data.insert("compute_auto_settlement", fields("contract_id", id, "member_id", memberId, "settlement_day", 1,
                "settlement_date", starts.toInstant(ZoneOffset.UTC).atZone(periods.zone()).toLocalDate(), "income", income, "principal", BigDecimal.ZERO, "created_at", starts));
        if (number(state.get("remainingCount")) == 1) repository.activateMemberWrite(ComputeVipPolicy.cooldown(starts, periods.zone(), number(level.get("autoCooldownSeconds"))), memberId);
        return record(memberId, orderNo);
    }

    public Map<String, Object> record(long memberId, String orderNo) {
        return publicRecord(repository.recordAutoContractRow(memberId, orderNo));
    }

    public Map<String, Object> status(long memberId) {
        Map<String, Object> member = repository.statusMemberRow(memberId);
        Map<String, Object> level = repository.statusLevelRow(member.get("level"));
        return statusOf(memberId, member, level, now());
    }

    private Map<String, Object> statusOf(long memberId, Map<String, Object> member, Map<String, Object> level, LocalDateTime time) {
        ZoneId zone = periods.zone();
        LocalDateTime dayStart = LocalDateTime.ofInstant(time.toInstant(ZoneOffset.UTC).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
        long count = repository.statusOfAutoContractCount(memberId, dayStart, ComputeVipPolicy.nextDay(time, zone));
        long limit = number(level.get("autoDailyLimit"));
        String currency = currencies.defaultCurrency();
        List<Map<String, Object>> wallets = repository.statusOfWalletRows(memberId, currency);
        BigDecimal balance = wallets.isEmpty() ? BigDecimal.ZERO : decimal(wallets.get(0).get("available"));
        BigDecimal allowed = balance.max(BigDecimal.ZERO);
        List<Map<String,Object>> pending = repository.statusOfAutoContractRows2(memberId);
        LocalDateTime expires = time(member.get("vipExpiresAt")), next = time(member.get("autoNextAt"));
        String reason = "";
        if (!enabled(level.get("enabled")) || !enabled(level.get("autoEnabled"))) reason = "server.compute.v6.autoDisabled";
        else if (expires != null && !expires.isAfter(time)) reason = "server.compute.v8.vipExpired";
        else if (next != null && next.isAfter(time) || count >= limit) {
            reason = "server.compute.v8.cooldown";
            LocalDateTime tomorrow = ComputeVipPolicy.nextDay(time, zone);
            if (count >= limit && (next == null || next.isBefore(tomorrow))) next = tomorrow;
        }
        return fields("serverTime", utc(time), "systemTimeZone", zone.getId(), "allowedAmount", allowed.toPlainString(), "currency", currency,
                "remainingCount", Math.max(0, limit - count), "dailyLimit", limit, "nextAllowedAt", next != null && next.isAfter(time) ? utc(next) : null,
                "canExecute", reason.isEmpty(), "reasonKey", reason, "vipExpiresAt", member.get("vipExpiresAt"),
                "pendingDetails", pending.isEmpty() ? null : publicRecord(pending.get(0)));
    }

    private Map<String, Object> publicRecord(Map<String, Object> contract) {
        contract.put("vipRewardMode",data.jsonMap(contract.get("ruleSnapshot")).get("vipRewardMode"));
        contract.remove("ruleSnapshot");
        contract.remove("memberId");
        contract.remove("requestId");
        boolean ready = "READY".equals(contract.get("detailsStatus"));
        Map<String,Object> result = ready && contract.get("executionResult") != null
                ? new LinkedHashMap<>(data.jsonMap(contract.get("executionResult"))) : fields("models", List.of());
        result.putAll(fields("executionState", "SUCCEEDED", "settledIncome", contract.get("paidIncome"),
                "expectedIncome", contract.get("paidIncome"), "currency", contract.get("currency"), "detailsStatus", contract.get("detailsStatus")));
        result.remove("source"); result.remove("usageSeed"); result.remove("stages"); result.remove("modelAllocations");
        contract.put("executionResult", result);
        return contract;
    }

    /** 短事务完成到期结果；多实例竞争同一用户锁，重启后由持久状态接续，重复调用不会重复付款。 */
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void finishDue() {
        try {
            for (Map<String,Object> row : repository.finishDueAutoContractRows2(now())) {
                try { transactions.executeWithoutResult(status -> finishDetails(number(row.get("id")), now())); }
                catch (Exception failure) { schedulerWarning(failure); }
            }
        } catch (Exception failure) { schedulerWarning(failure); }
    }

    private void schedulerWarning(Exception failure) {
        long current = System.currentTimeMillis();
        if (current - lastSchedulerWarning < 60_000) return;
        lastSchedulerWarning = current;
        log.error("VIP 算力任务暂不能完成，请检查当前增量与账本；保留记录等待重试", failure);
    }

    /** 明细到期只生成展示数据，绝不再次发放收益。 */
    @Transactional
    public void finishDetails(long id, LocalDateTime at) {
        Map<String,Object> row = repository.finishDetailsAutoContractRow(id);
        if (!"PENDING".equals(row.get("detailsStatus")) || time(row.get("resultAvailableAt")).isAfter(at)) return;
        Map<String,Object> plan = new LinkedHashMap<>(data.jsonMap(data.json(data.jsonMap(row.get("ruleSnapshot")).get("usagePlan"))));
        // 旧记录没有种子时使用订单号，重试不生成不同数值。
        plan = ComputeUsageDetails.generate(plan, plan.containsKey("usageSeed") ? number(plan.get("usageSeed")) : text(row.get("orderNo")).hashCode());
        plan.put("generatedAt", utc(at));
        repository.finishDetailsAutoContractWrite(data.json(plan), id);
    }

    private static void validateClickRules(Map<String,Object> level) {
        long display=number(level.get("autoDisplaySeconds")), delay=number(level.get("vipDetailsDelayMinutes"));
        if (display<1 || display>60 || delay<1 || delay>1380
                || number(level.get("autoDailyLimit"))<1 || number(level.get("autoDailyLimit"))>1000
                || number(level.get("autoCooldownSeconds"))<0 || number(level.get("autoCooldownSeconds"))>31_536_000
                || !Set.of("RATE","FIXED").contains(text(level.get("vipRewardMode")))
                || decimal(level.get("vipFixedReward")).signum()<0
                || decimal(level.get("autoClickRateMin")).signum()<0
                || decimal(level.get("autoClickRateMax")).compareTo(decimal(level.get("autoClickRateMin")))<0
                || decimal(level.get("autoClickRateMax")).compareTo(BigDecimal.valueOf(100))>0)
            throw new BusinessException("server.compute.invalidInput");
    }

    private static LocalDateTime time(Object value) { return text(value).isEmpty() ? null : LocalDateTime.ofInstant(Instant.parse(text(value)), ZoneOffset.UTC); }
    private static String utc(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC).toString(); }

}
