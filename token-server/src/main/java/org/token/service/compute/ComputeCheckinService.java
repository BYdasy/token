package org.token.service.compute;

import org.token.repository.compute.ComputeCheckinRepository;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeTokenAmounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 系统自然日签到，会员行锁、唯一日记录与原钱包账本在同一事务中提交。 */
@Service
public class ComputeCheckinService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeCheckinService(ComputeData data, ComputeUserFeaturesService features, ComputeBusinessService business, SystemSettingService settings, ComputeAgentScope scope, ComputeCheckinRepository repository) {
        this.data = data;
        this.features = features;
        this.business = business;
        this.settings = settings;
        this.scope = scope;
        this.repository = repository;
    }

    public ComputeCheckinService(ComputeData data, ComputeUserFeaturesService features, ComputeBusinessService business, SystemSettingService settings, ComputeAgentScope scope) {
        this(data, features, business, settings, scope, new ComputeCheckinRepository(data));
    }

    private final ComputeData data;
    private final ComputeCheckinRepository repository;
    private final ComputeUserFeaturesService features;
    private final ComputeBusinessService business;
    private final SystemSettingService settings;
    private final ComputeAgentScope scope;

    public Map<String, Object> status(long memberId) {
        Map<String, Object> config = requiredSettings();
        LocalDate today = day(ZoneId.of(settings.resolveSystemTimeZone()));
        var records = repository.statusCheckinRows(memberId);
        boolean checked = records.stream().anyMatch(row -> today.toString().equals(text(row.get("checkinDate"))));
        long streak = records.isEmpty() ? 0 : number(records.get(0).get("streakDays"));
        if (!records.isEmpty() && !Set.of(today.toString(), today.minusDays(1).toString()).contains(text(records.get(0).get("checkinDate")))) streak = 0;
        return fields("checked", checked, "today", today.toString(), "streak", streak, "dailyReward", config.get("dailyReward"),
                "cycleDays", config.get("cycleDays"), "cycleBonus", config.get("cycleBonus"), "records", records);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> checkin(long memberId) {
        Map<String, Object> config = requiredSettings();
        repository.checkinMemberRow(memberId);
        ZoneId zone = ZoneId.of(settings.resolveSystemTimeZone());
        LocalDate today = day(zone);
        if (repository.checkinCheckinCount(memberId, today) != 0) return status(memberId);
        var previous = repository.checkinCheckinRows(memberId);
        // 时区向西调整导致日期倒退时不倒序发奖；时区始终不改历史签到日。
        if (!previous.isEmpty() && LocalDate.parse(text(previous.get(0).get("checkinDate"))).isAfter(today))
            throw new BusinessException("server.compute.v11.checkinWait");
        long streak = !previous.isEmpty() && today.minusDays(1).toString().equals(text(previous.get(0).get("checkinDate")))
                ? number(previous.get(0).get("streakDays")) + 1 : 1;
        BigDecimal token = decimal(config.get("dailyReward"));
        if (streak % number(config.get("cycleDays")) == 0) token = token.add(decimal(config.get("cycleBonus")));
        String currency = business.defaultCurrency();
        BigDecimal rate = token.signum() == 0 ? BigDecimal.ZERO : ComputeTokenAmounts.rate(data, currency);
        BigDecimal nativeReward = token.signum() == 0 ? BigDecimal.ZERO : ComputeTokenAmounts.toNative(token, rate);
        long id = data.insert("compute_checkin", fields("member_id", memberId, "checkin_date", today, "system_time_zone", zone.getId(),
                "streak_days", streak, "reward_token", token, "currency", currency, "reward_amount", nativeReward, "asset_rate", rate, "created_at", now()));
        if (nativeReward.signum() > 0) business.changeWallet(memberId, "EARNINGS", currency, nativeReward, BigDecimal.ZERO, "CHECKIN_REWARD", "CHECKIN:" + id, String.valueOf(id));
        return status(memberId);
    }

    public Map<String, Object> records(Map<String, String> query) {
        int page = (int) Math.max(1, Math.min(100000, number(query.getOrDefault("page", "1"))));
        int size = (int) Math.max(1, Math.min(100, number(query.getOrDefault("size", "20"))));
        return repository.records(scope.queryScope(), text(query.get("username")), page, size);
    }

    LocalDate day(ZoneId zone) { return LocalDate.now(zone); }
    private Map<String, Object> requiredSettings() {
        var config = features.settings();
        if (!enabled(config.get("checkinEnabled"))) throw new BusinessException("server.compute.v11.featureDisabled");
        return config;
    }
}
