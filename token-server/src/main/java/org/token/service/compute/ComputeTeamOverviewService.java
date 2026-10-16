package org.token.service.compute;

import org.token.model.query.TeamReportPeriod;

import org.token.repository.compute.ComputeTeamOverviewRepository;

import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 当前用户可见层级的团队概览；收益仅为本人实际收到的下级充值及收益返点。 */
@Service
public class ComputeTeamOverviewService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeTeamOverviewService(ComputeData data, ComputeMemberRelations relations, SystemSettingService settings, ComputeLevelNames levelNames, ComputeTeamOverviewRepository repository) {
        this.data = data;
        this.relations = relations;
        this.settings = settings;
        this.levelNames = levelNames;
        this.repository = repository;
    }

    public ComputeTeamOverviewService(ComputeData data, ComputeMemberRelations relations, SystemSettingService settings) {
        this(data, relations, settings, null, new ComputeTeamOverviewRepository(data));
    }

    private static final int MAX_DAYS = 93;
    private static final Set<String> FIXED_REWARD_CURRENCIES = Set.of("USD", "USDT", "USDC", "USDT0");
    private final ComputeData data;
    private final ComputeTeamOverviewRepository repository;
    private final ComputeMemberRelations relations;
    private final SystemSettingService settings;
    private final ComputeLevelNames levelNames;

    /** 首页邀请摘要与团队页共用范围和到账来源，不读取明细、趋势或当前币价。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> invitation(long memberId) {
        repository.overviewMemberRow(memberId);
        relations.stats(memberId);
        int supportedDepth = supportedDepth();
        long memberCount = repository.memberCount(memberId, supportedDepth);
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        List<Map<String, Object>> rewards = new ArrayList<>();
        for (var row : repository.rewardTotals(memberId, supportedDepth)) {
            String currency = text(row.get("currency"));
            BigDecimal amount = decimal(row.get("totalAmount"));
            rewards.add(fields("currency", currency, "amount", amount.toPlainString()));
            if (FIXED_REWARD_CURRENCIES.contains(currency)) total = total.add(amount);
            else complete = false;
        }
        return fields("memberCount", memberCount, "totalRewardsToken", total.setScale(8, RoundingMode.HALF_UP).toPlainString(),
                "rewardsByCurrency", rewards, "complete", complete);
    }

    /** 整个返回采用同一关系与资金快照，分页只影响成员明细，不改变概览和图表口径。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> overview(long memberId, Map<String, String> query) {
        return overview(memberId, query, Instant.now());
    }

    Map<String, Object> overview(long memberId, Map<String, String> query, Instant instant) {
        repository.overviewMemberRow(memberId);
        // 先核验完整子树，发现环或超过现有关系上限时明确失败，不返回截断统计。
        relations.stats(memberId);
        ZoneId zone = ZoneId.of(settings.resolveSystemTimeZone());
        LocalDate today = instant.atZone(zone).toLocalDate();
        TeamReportPeriod period = period(query, today, zone);
        int page = positive(query.get("page"), 1, 1_000_000);
        int size = positive(query.get("pageSize"), 10, 50);
        int supportedDepth = supportedDepth();
        Integer requestedDepth = text(query.get("depth")).isEmpty() ? null : positive(query.get("depth"), 1, ComputeMemberRelations.MAX_DEPTH);
        // 配置缩短后，旧页面选中的层级恢复为全部可见层级；不会向下扩展查询范围。
        Integer depth = requestedDepth != null && requestedDepth <= supportedDepth ? requestedDepth : null;
        List<Map<String, Object>> trend = period.days().stream().map(day -> fields("day", day.toString(), "newMembers", 0L,
                "directInvites", 0L, "rewardToken", "0")).toList();
        Map<String, Object> summary = memberSummary(memberId, supportedDepth, period, today, zone, trend);
        Map<Long, Map<String, Object>> actualStructure = new HashMap<>();
        repository.structure(memberId, supportedDepth, period.start(), period.end())
                .forEach(row -> actualStructure.put(number(row.get("depth")), row));
        List<Map<String, Object>> structure = new ArrayList<>();
        for (long level = 1; level <= supportedDepth; level++) structure.add(actualStructure.getOrDefault(level,
                fields("depth", level, "memberCount", 0L, "newMembers", 0L)));
        Set<String> missing = new TreeSet<>();
        long unpricedCount = 0;
        summary.put("totalRewardToken", "0");
        summary.put("todayRewardToken", "0");
        summary.put("periodRewardToken", "0");
        for (var row : repository.rewardSummary(memberId, supportedDepth, period, today, zone)) {
            String currency = text(row.get("currency"));
            // 真实钱包的稳定计价单位始终1:1；旧非稳定币返点没有固定计价时明确标记，不按当前行情回算。
            if (!FIXED_REWARD_CURRENCIES.contains(currency)) {
                missing.add(currency);
                unpricedCount += number(row.get("recordCount"));
                continue;
            }
            add(summary, "totalRewardToken", decimal(row.get("totalAmount")));
            add(summary, "todayRewardToken", decimal(row.get("todayAmount")));
            add(summary, "periodRewardToken", decimal(row.get("periodAmount")));
            for (int index = 0; index < trend.size(); index++) {
                add(trend.get(index), "rewardToken", decimal(row.get("d" + index)));
            }
        }
        // 只公开姓名、直属关系、VIP及注册时间；不返回数据库ID、联系方式和个人钱包金额。
        List<Map<String, Object>> members = repository.members(memberId, supportedDepth, depth, size, (long) (page - 1) * size);
        if (levelNames != null) levelNames.decorate(members, "level", "levelName");
        long total = depth == null ? number(summary.get("memberCount")) : structure.stream()
                .filter(row -> number(row.get("depth")) == depth).mapToLong(row -> number(row.get("memberCount"))).sum();
        return fields("timeZone", zone.getId(), "fromDate", period.days().get(0).toString(), "toDate", period.days().get(period.days().size() - 1).toString(),
                "includesSelf", false, "supportedDepth", supportedDepth, "summary", summary, "structure", structure, "trend", trend,
                "rewardValuation", fields("unit", "token", "complete", missing.isEmpty(), "unpricedCurrencies", missing, "unpricedCount", unpricedCount),
                "members", fields("records", members, "total", total, "page", page, "pageSize", size, "depth", depth));
    }

    private int supportedDepth() {
        return (int) Math.min(ComputeMemberRelations.MAX_DEPTH, Math.max(1,
                number(data.setting("teamSupportedDepth", "3"))));
    }

    private Map<String, Object> memberSummary(long memberId, int supportedDepth, TeamReportPeriod period, LocalDate today, ZoneId zone, List<Map<String, Object>> trend) {
        Map<String, Object> summary = repository.memberSummary(memberId, supportedDepth, period, today, zone);
        for (int index = 0; index < trend.size(); index++) {
            trend.get(index).put("newMembers", summary.remove("n" + index));
            trend.get(index).put("directInvites", summary.remove("i" + index));
        }
        summary.put("totalInvites", summary.get("memberCount"));
        return summary;
    }

    private static TeamReportPeriod period(Map<String, String> query, LocalDate today, ZoneId zone) {
        try {
            LocalDate to = text(query.get("toDate")).isEmpty() ? today : LocalDate.parse(query.get("toDate"));
            LocalDate from = text(query.get("fromDate")).isEmpty() ? to.minusDays(6) : LocalDate.parse(query.get("fromDate"));
            if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw new DateTimeException("日期范围无效");
            List<LocalDate> days = from.datesUntil(to.plusDays(1)).toList();
            return new TeamReportPeriod(days, start(from, zone), start(to.plusDays(1), zone), zone);
        } catch (DateTimeException invalid) { throw new BusinessException("server.compute.ui2.teamDateRange"); }
    }

    private static LocalDateTime start(LocalDate day, ZoneId zone) { return LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(), ZoneOffset.UTC); }
    private static int positive(String value, int fallback, int max) {
        if (text(value).isEmpty()) return fallback;
        long parsed = number(value);
        if (parsed < 1 || parsed > max) throw new BusinessException("server.compute.invalidInput");
        return (int) parsed;
    }
    private static void add(Map<String, Object> target, String key, BigDecimal value) {
        target.put(key, decimal(target.get(key)).add(value).setScale(8, RoundingMode.HALF_UP).toPlainString());
    }

}
