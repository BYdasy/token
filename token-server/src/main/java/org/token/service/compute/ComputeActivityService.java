package org.token.service.compute;

import org.token.repository.compute.ComputeActivityRepository;

import org.token.service.compute.ComputeActivityRules;
import org.token.service.compute.ComputeDisplayNames;

import org.token.service.compute.ComputeBusinessService;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;
import static org.token.service.compute.ComputeActivityRules.nonnegative;

/** 活动事务始终按会员、活动、钱包顺序加锁；额度、库存、记录与奖励账本一同提交。 */
@Service
public class ComputeActivityService {
    private static final SecureRandom CODE_RANDOM = new SecureRandom();
    private static final String CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String CODE_DIGITS = "23456789";
    private final ComputeData data;
    private final ComputeActivityRepository repository;
    private final ComputeBusinessService wallets;
    private final ComputeAgentScope scope;
    private final SystemSettingService systemSettings;
    private final ComputeWheelService wheel;
    private final ComputeLevelNames levelNames;

    public ComputeActivityService(JdbcTemplate jdbc, ComputeData data, ComputeBusinessService wallets,
                                  ComputeAgentScope scope, SystemSettingService systemSettings, ComputeWheelService wheel, ComputeLevelNames levelNames) {
        this(new ComputeActivityRepository(data),data,wallets,scope,systemSettings,wheel,levelNames);
    }
    @Autowired
    public ComputeActivityService(ComputeActivityRepository repository,ComputeData data,ComputeBusinessService wallets,
                                  ComputeAgentScope scope,SystemSettingService systemSettings,ComputeWheelService wheel,ComputeLevelNames levelNames) {
        this.repository=repository;this.data=data;this.wallets=wallets;this.scope=scope;this.systemSettings=systemSettings;this.wheel=wheel;this.levelNames=levelNames;
    }

    public ComputeActivityService(JdbcTemplate jdbc, ComputeData data, ComputeBusinessService wallets, ComputeAgentScope scope, SystemSettingService systemSettings, ComputeWheelService wheel) {
        this(jdbc, data, wallets, scope, systemSettings, wheel, null);
    }

    public ComputeActivityService(JdbcTemplate jdbc, ComputeData data, ComputeBusinessService wallets, ComputeAgentScope scope, SystemSettingService systemSettings) {
        this(jdbc, data, wallets, scope, systemSettings, null);
    }

    /** 保留独立领域测试构造方式；生产实例由 Spring 注入系统设置。 */
    public ComputeActivityService(JdbcTemplate jdbc, ComputeData data, ComputeBusinessService wallets, ComputeAgentScope scope) {
        this(jdbc, data, wallets, scope, null);
    }

    public Map<String, Object> adminPage(Map<String, String> query) {
        if (enabled(query.get("wheelOptions")) && wheel != null) {
            List<Map<String,Object>> records = new ArrayList<>();
            String requestedType=text(query.get("type"));if(requestedType.isBlank())requestedType="WHEEL";
            if(!Set.of("WHEEL","CHEST").contains(requestedType))throw invalid();
            List<Map<String,Object>> options="CHEST".equals(requestedType)?repository.adminPageActivityRows():repository.adminPageActivityRows2(primaryWheel());
            for(Map<String,Object> row:options)if(wheel.reference(row))
                records.add(fields("id",row.get("id"),"name",row.get("name")));
            return Map.of("records",records,"total",records.size());
        }
        scope.requireGlobalScope();
        Map<String, Object> result = activityPage(query, false, null);
        result.put("canManageGlobal", true);
        return result;
    }

    public Map<String, Object> publicPage(Map<String, String> query, Long memberId) {
        return activityPage(query, true, memberId);
    }

    private Map<String, Object> activityPage(Map<String, String> query, boolean publicOnly, Long memberId) {
        Map<String,Object> result=repository.activityPage(query,publicOnly,primaryWheel());
        @SuppressWarnings("unchecked") List<Map<String,Object>> records=(List<Map<String,Object>>)result.get("records");
        records.forEach(row -> decorate(row, memberId));
        if (publicOnly && levelNames != null) levelNames.decorate(records, "minLevel", "minLevelName");
        return result;
    }

    public Map<String, Object> detail(long id, Long memberId) {
        Map<String, Object> row = repository.detailActivityRow(id);
        assertCurrentWheel(row);
        decorate(row, memberId);
        if (levelNames != null) row.put("minLevelName", levelNames.name(row.get("minLevel")));
        return row;
    }

    private void decorate(Map<String, Object> row, Long memberId) {
        long id = number(row.get("id"));
        List<Map<String, Object>> prizes = repository.decorateActivityPrizeRows(id);
        row.put("prizes", prizes);
        if (wheel != null) {
            row.put("wheelConfig", wheel.config(row));
            if (memberId != null && wheel.reference(row)) {
                row.put("myWheel", wheel.memberStatus(memberId, id));
                row.put("wheelConfig",wheel.config(repository.decorateActivityRow(id)));
            }
        }
        long remainingStock = number(row.get("totalStock")) - number(row.get("usedStock"));
        BigDecimal remainingBudget = decimal(row.get("totalBudget")).subtract(decimal(row.get("usedBudget")));
        row.put("remainingStock", remainingStock); row.put("remainingBudget", remainingBudget.toPlainString());
        LocalDateTime current = now();
        boolean attemptActivity=Set.of("WHEEL","CHEST").contains(row.get("type"));
        boolean exhausted = attemptActivity ? "WHEEL".equals(row.get("type"))&&prizes.stream().noneMatch(prize ->
                ComputeWheelService.probability(prize).signum()>0 && number(prize.get("stock")) > number(prize.get("usedStock"))) : remainingStock<=0;
        String status = !enabled(row.get("enabled")) ? "DISABLED" : current.isBefore(time(row.get("startsAt"))) ? "UPCOMING"
                : !current.isBefore(time(row.get("endsAt"))) ? "ENDED" : exhausted ? "EXHAUSTED" : "OPEN";
        if(attemptActivity&&(wheel==null||!wheel.reference(row))) {row.put("attemptsConfigurationRequired",true);if("OPEN".equals(status))status="CONFIG_REQUIRED";}
        row.put("status", status);
        if (memberId != null) {
            row.put("myTotalCount", participationCount(row, memberId, null));
            row.put("myTodayCount", participationCount(row, memberId, current));
        }
    }

    @Transactional
    public Map<String, Object> save(Map<String, Object> request) {
        scope.requireGlobalScope();
        // 所有主转盘写操作共享设置行锁，不能并发创建第二场。
        repository.saveSettingWrite(now());
        long lockedWheel=number(repository.saveSettingValue());
        Long id = request.get("id") == null ? null : number(request.get("id"));
        Map<String, Object> previous = id == null ? null : repository.saveActivityRow(id);
        if(previous!=null&&wheel!=null)wheel.refreshCycle(previous);
        long usedStock = previous == null ? 0 : number(previous.get("usedStock"));
        BigDecimal usedBudget = previous == null ? BigDecimal.ZERO : decimal(previous.get("usedBudget"));
        String type = required(request.get("type"), 20);
        if (!Set.of("WHEEL", "RED_PACKET", "CHEST").contains(type)) throw invalid();
        if(previous!=null&&!type.equals(previous.get("type")))throw invalid();
        if("WHEEL".equals(type)&&((id==null&&lockedWheel>0)||(id!=null&&id!=lockedWheel)))throw new BusinessException("server.compute.v12.singleWheel");
        String currency = request.containsKey("currency") ? required(request.get("currency"), 16)
                : previous == null ? ComputeUsdAccounting.WALLET_CURRENCY : text(previous.get("currency"));
        if (previous == null && !Set.of("USD", "USDT", "USDC").contains(currency))
            throw new BusinessException("server.compute.unsupportedCurrency");
        if (previous == null) currency = ComputeUsdAccounting.WALLET_CURRENCY;
        if (repository.saveCurrencyCount(currency) == 0)
            throw new BusinessException("server.compute.unsupportedCurrency");
        LocalDateTime starts = time(request.get("startsAt")), ends = time(request.get("endsAt"));
        if (!ends.isAfter(starts)) throw new BusinessException("server.compute.activity.invalidTime");
        boolean attempts=Set.of("WHEEL","CHEST").contains(type);
        long totalStock = attempts?Math.max(usedStock,previous==null?1:number(previous.get("totalStock"))):integer(request.get("totalStock"), 1, 1_000_000_000L);
        BigDecimal budget = attempts?usedBudget.max(previous==null?BigDecimal.ZERO:decimal(previous.get("totalBudget"))):nonnegative(request.get("totalBudget"));
        BigDecimal minimum = nonnegative(request.getOrDefault("minReward", "0")), maximum = nonnegative(request.getOrDefault("maxReward", "0"));
        String packetMode = text(request.getOrDefault("packetMode", "FIXED"));
        if (!Set.of("FIXED", "RANDOM").contains(packetMode)) throw invalid();
        if (totalStock < usedStock || budget.compareTo(usedBudget) < 0) throw new BusinessException("server.compute.activity.usedBudget");
        List<Map<String, Object>> prizes = normalizePrizes(request.get("prizes"));
        if ("WHEEL".equals(type) && prizes.size()!=8) throw new BusinessException("server.compute.v12.eightPrizes");
        // 转盘和宝箱由实际可用次数控制，不再使用旧的活动预算、活动份数及每日/累计上限。
        Object wheelConfiguration=request.get("wheelConfig");
        if("CHEST".equals(type)) {
            if(!prizes.isEmpty()||!(wheelConfiguration instanceof Map<?,?> raw))throw invalid();
            Map<String,Object> cfg=new LinkedHashMap<>();raw.forEach((key,value)->cfg.put(String.valueOf(key),value));
            if(!cfg.containsKey("chestRanges")) {
                if(previous!=null)cfg.put("chestRanges",wheel.config(previous).get("chestRanges"));
                else cfg.put("chestRanges",List.of(fields("id","legacy-range","minReward",minimum.toPlainString(),"maxReward",maximum.toPlainString(),"weight","100")));
            }
            List<Map<String,Object>> ranges=ComputeWheelService.normalizeChestRanges(cfg.get("chestRanges"));
            minimum=ranges.stream().map(r->decimal(r.get("minReward"))).min(BigDecimal::compareTo).orElseThrow();
            maximum=ranges.stream().map(r->decimal(r.get("maxReward"))).max(BigDecimal::compareTo).orElseThrow();
            cfg.put("chestRanges",ranges);wheelConfiguration=cfg;
        }

        if ("RED_PACKET".equals(type)) {
            if ("FIXED".equals(packetMode) && minimum.compareTo(maximum) != 0) throw new BusinessException("server.compute.activity.packetBudgetInvalid");
            ComputeActivityRules.validatePacket(budget.subtract(usedBudget), totalStock - usedStock, minimum, maximum);
            if (!prizes.isEmpty()) throw invalid();
        }
        if (usedStock > 0) {
            List<Map<String, Object>> currentPrizes = normalizePrizes(repository.decorateActivityPrizeRows(id));
            boolean editablePrizes = attempts;
            if (!type.equals(previous.get("type")) || !currency.equals(previous.get("currency")) || (!editablePrizes && !prizes.equals(currentPrizes))
                    || (!attempts&&(!packetMode.equals(previous.get("packetMode")) || minimum.compareTo(decimal(previous.get("minReward"))) != 0
                    || maximum.compareTo(decimal(previous.get("maxReward"))) != 0)))
                throw new BusinessException("server.compute.activity.prizesLocked");
        }
        Map<String, Object> values = fields("name", required(request.get("name"), 160), "type", type,
                "description", limited(request.get("description"), 10000), "currency", currency, "enabled", enabled(request.get("enabled")),
                "starts_at", starts, "ends_at", ends, "min_level", attempts?1:integer(request.getOrDefault("minLevel", 1), 1, 100000),
                "min_deposit", attempts?BigDecimal.ZERO:nonnegative(request.getOrDefault("minDeposit", "0")), "min_active_principal", attempts?BigDecimal.ZERO:nonnegative(request.getOrDefault("minActivePrincipal", "0")),
                "per_user_total_limit", attempts?1000000:integer(request.getOrDefault("perUserTotalLimit",1),1,1000000),
                "per_user_daily_limit", attempts?1000000:integer(request.getOrDefault("perUserDailyLimit",1),1,1000000),
                "total_stock", totalStock, "total_budget", budget, "packet_mode", packetMode, "min_reward", minimum, "max_reward", maximum, "updated_at", now());
        boolean floating=enabled(request.get("floatingEnabled"));String floatingImage=ComputeWheelService.image(request.get("floatingImage"));
        if(floating&&floatingImage.isBlank())throw invalid();
        values.put("floating_enabled",floating);values.put("floating_image",floatingImage);
        values.put("floating_priority",integer(request.getOrDefault("floatingPriority",0),-1000000,1000000));
        if (wheel != null && attempts) values.put("wheel_config", data.json(wheel.normalize(wheelConfiguration, previous,type)));
        else if(wheel!=null&&"RED_PACKET".equals(type)) {
            Map<String,Object> presentation=previous==null?new LinkedHashMap<>():wheel.config(previous);
            if(wheelConfiguration instanceof Map<?,?> supplied) {
                if(supplied.containsKey("bannerImage"))presentation.put("bannerImage",ComputeWheelService.image(supplied.get("bannerImage")));
                if(supplied.containsKey("floatingBottom"))presentation.put("floatingBottom",integer(supplied.get("floatingBottom"),0,2000));
            }
            values.put("wheel_config",data.json(presentation));
        }
        if (id == null) { values.put("created_at", now()); id = data.insert("compute_activity", values); if("WHEEL".equals(type))repository.saveSettingWrite2(String.valueOf(id), now()); }
        else {
            List<Object> args = new ArrayList<>(values.values()); args.add(id);
            data.update("compute_activity", id, values);
        }
        // 尚未抽奖但已指定用户奖项时也保留奖项编号，修改横幅或自动到账开关不能让指定失效。
        boolean assignedPrize=previous!=null&&"WHEEL".equals(type)&&repository.saveWheelMemberCount(id, wheel.config(previous).getOrDefault("round",1))>0;
        if (usedStock == 0 && !assignedPrize) {
            repository.saveActivityPrizeWrite(id);
            for (int index = 0; index < prizes.size(); index++) {
                Map<String, Object> prize = new LinkedHashMap<>(prizes.get(index)); prize.put("activity_id", id); prize.put("sort_order", index);
                data.insert("compute_activity_prize", prize);
            }
        } else if ("WHEEL".equals(type)) {
            List<?> requested=(List<?>)request.get("prizes");
            List<Map<String,Object>> existing=repository.saveActivityPrizeRows(id);
            Set<Long> retained=new HashSet<>();
            for(int index=0;index<prizes.size();index++) {
                Map<?,?> source=(Map<?,?>)requested.get(index);Object rawId=source.get("id");
                Map<String,Object> valuesPrize=new LinkedHashMap<>(prizes.get(index));valuesPrize.put("sort_order",index);
                if(rawId==null){valuesPrize.put("activity_id",id);data.insert("compute_activity_prize",valuesPrize);continue;}
                long prizeId=number(rawId);Map<String,Object> currentPrize=existing.stream().filter(row->number(row.get("id"))==prizeId).findFirst().orElseThrow(ComputeActivityService::invalid);
                if(!retained.add(prizeId)||number(valuesPrize.get("stock"))<number(currentPrize.get("usedStock")))throw invalid();
                List<Object> argsPrize=new ArrayList<>(valuesPrize.values());argsPrize.add(prizeId);
                data.update("compute_activity_prize", prizeId, valuesPrize);
            }
            if(retained.size()!=existing.size())throw new BusinessException("server.compute.activity.prizesLocked");
        }
        Map<String, Object> result = repository.saveActivityRow2(id); decorate(result, null); return result;
    }

    @Transactional
    public void delete(long id) {
        scope.requireGlobalScope();
        Map<String, Object> activity = repository.saveActivityRow(id);
        if("WHEEL".equals(activity.get("type")))throw new BusinessException("server.compute.v12.singleWheel");
        if (enabled(activity.get("enabled")) || number(activity.get("usedStock")) > 0)
            throw new BusinessException("server.compute.activity.cannotDelete");
        repository.saveActivityPrizeWrite(id);
        repository.deleteActivityWrite(id);
    }

    @Transactional
    public Map<String, Object> participate(long memberId, long activityId, String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,80}")) throw new BusinessException("server.compute.activity.invalidRequest");
        Map<String, Object> member = repository.participateMemberRow(memberId);
        List<Map<String, Object>> previous = repository.participateActivityParticipationRows(memberId, requestId);
        if (!previous.isEmpty()) {
            if (number(previous.get(0).get("activityId")) != activityId) throw new BusinessException("server.compute.requestConflict");
            return publicRecord(previous.get(0));
        }
        Map<String, Object> activity = repository.saveActivityRow(activityId);
        assertCurrentWheel(activity);
        LocalDateTime current = now();
        if (!enabled(activity.get("enabled")) || current.isBefore(time(activity.get("startsAt"))) || !current.isBefore(time(activity.get("endsAt"))))
            throw new BusinessException("server.compute.activity.notOpen");
        boolean attempts=Set.of("WHEEL","CHEST").contains(activity.get("type"));
        if(!attempts)checkEligibility(activity, memberId, number(member.get("level")), current);
        if(attempts&&(wheel==null||!wheel.reference(activity)))throw new BusinessException("server.compute.activity.configurationRequired");
        long remainingStock = number(activity.get("totalStock")) - number(activity.get("usedStock"));
        BigDecimal remainingBudget = decimal(activity.get("totalBudget")).subtract(decimal(activity.get("usedBudget")));
        if (!attempts&&remainingStock <= 0) throw new BusinessException("server.compute.activity.exhausted");
        boolean reference = "WHEEL".equals(activity.get("type"))&&wheel != null && wheel.reference(activity);
        Long prizeId = null;String rewardRangeId=null; String prizeName = text(activity.get("name")); BigDecimal reward; boolean noPrize=false,autoCredit=false;
        if("CHEST".equals(activity.get("type"))) {
            Map<String,Object> selected=wheel.chooseChest(memberId,activity);rewardRangeId=text(selected.get("id"));
            reward=ComputeActivityRules.rangeAmount(decimal(selected.get("minReward")),decimal(selected.get("maxReward")));
        } else if ("RED_PACKET".equals(activity.get("type"))) {
            reward = ComputeActivityRules.packetAmount(remainingBudget, remainingStock, decimal(activity.get("minReward")), decimal(activity.get("maxReward")));
        } else {
            List<Map<String,Object>> configured = repository.participateActivityPrizeRows(activityId);
            List<ComputeActivityRules.Prize> choices = configured.stream().filter(prize->ComputeWheelService.probability(prize).signum()>0)
                    .map(prize -> new ComputeActivityRules.Prize(number(prize.get("id")), text(prize.get("name")), decimal(prize.get("amount")),
                            ComputeWheelService.probability(prize).movePointRight(6).longValueExact(), number(prize.get("stock")) - number(prize.get("usedStock")))).toList();
            if (reference) {
                Map<String,Object> prize = wheel.choose(memberId, activity, configured, null);
                prizeId=number(prize.get("id"));prizeName=text(prize.get("name"));reward=decimal(prize.get("amount"));
                noPrize=enabled(prize.get("isNoPrize"));
                autoCredit=enabled(prize.get("autoCredit"));
            } else {
                ComputeActivityRules.Prize prize = ComputeActivityRules.choosePrize(choices, remainingBudget);
                prizeId = prize.id(); prizeName = prize.name(); reward = prize.amount();
            }
            if (repository.participateActivityPrizeWrite(prizeId) != 1)
                throw new BusinessException("server.compute.activity.exhausted");
        }
        if(attempts)repository.participateActivityWrite(reward, reward, current, activityId);
        else if (repository.participateActivityWrite2(reward, current, activityId, reward) != 1) throw new BusinessException("server.compute.activity.exhausted");
        Map<String,Object> recordValues=fields("activity_id", activityId, "activity_name", activity.get("name"),
                "activity_type", activity.get("type"), "member_id", memberId, "request_id", requestId, "prize_id", prizeId, "prize_name", prizeName,
                "amount", reward, "currency", activity.get("currency"), "status", noPrize ? "NO_PRIZE" : reference&&!autoCredit ? "PENDING_REDEMPTION" : reward.signum() > 0 ? "PAID" : "NO_PRIZE",
                "activity_snapshot", data.json(activity), "created_at", current);
        if(rewardRangeId!=null)recordValues.put("reward_range_id",rewardRangeId);
        if(reference&&!noPrize&&!autoCredit)recordValues.put("redemption_code",nextRedemptionCode());
        if(reference&&!noPrize&&autoCredit) {
            recordValues.put("redemption_type","AUTO");
            recordValues.put("redeemed_at",current);
        }
        if(attempts)recordValues.put("wheel_round",wheel.config(activity).getOrDefault("round",1));
        long participationId = data.insert("compute_activity_participation", recordValues);
        if ((!reference || autoCredit) && !noPrize && reward.signum() > 0) wallets.creditActivityReward(memberId, text(activity.get("currency")), reward, participationId);
        return publicRecord(repository.participateActivityParticipationRow(participationId));
    }

    private void checkEligibility(Map<String, Object> activity, long memberId, long level, LocalDateTime current) {
        if (level < number(activity.get("minLevel"))) throw new BusinessException("server.compute.activity.levelRequired");
        String currency = text(activity.get("currency")); long activityId = number(activity.get("id"));
        String depositUnit = Set.of("USD", "USDT", "USDC").contains(currency) ? ComputeUsdAccounting.WALLET_CURRENCY : currency;
        BigDecimal deposits = repository.checkEligibilityDepositValue(memberId, depositUnit);
        BigDecimal principal = repository.checkEligibilityOrderValue(memberId, depositUnit);
        if (deposits == null || deposits.compareTo(decimal(activity.get("minDeposit"))) < 0) throw new BusinessException("server.compute.activity.depositRequired");
        if (principal == null || principal.compareTo(decimal(activity.get("minActivePrincipal"))) < 0) throw new BusinessException("server.compute.activity.principalRequired");
        if (participationCount(activity, memberId, null) >= number(activity.get("perUserTotalLimit")))
            throw new BusinessException("server.compute.activity.totalLimit");
        if (participationCount(activity, memberId, current) >= number(activity.get("perUserDailyLimit")))
            throw new BusinessException("server.compute.activity.dailyLimit");
    }

    /** 在 Java 中将系统自然日两端换算成 UTC，兼容夏令时的二十三或二十五小时日期。 */
    long todayCount(long activityId, long memberId, LocalDateTime utcCurrent) {
        ZoneId zone = ZoneId.of(systemSettings == null ? "UTC" : systemSettings.resolveSystemTimeZone());
        LocalDate day = utcCurrent.toInstant(ZoneOffset.UTC).atZone(zone).toLocalDate();
        LocalDateTime start = LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
        LocalDateTime end = LocalDateTime.ofInstant(day.plusDays(1).atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
        return repository.todayCountActivityParticipationCount(activityId, memberId, start, end);
    }

    private long participationCount(Map<String,Object> activity,long memberId,LocalDateTime current) {
        long id=number(activity.get("id"));
        if(wheel==null||!wheel.reference(activity))return current==null?repository.participationCountActivityParticipationCount(id, memberId):todayCount(id,memberId,current);
        LocalDateTime start=null,end=null;
        if(current!=null){
            ZoneId zone=ZoneId.of(systemSettings==null?"UTC":systemSettings.resolveSystemTimeZone());
            LocalDate day=current.toInstant(ZoneOffset.UTC).atZone(zone).toLocalDate();
            start=LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
            end=LocalDateTime.ofInstant(day.plusDays(1).atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
        }
        return repository.participationCount(id,memberId,number(wheel.config(activity).getOrDefault("round",1)),start,end);
    }

    public Map<String, Object> participations(Long memberId, Map<String, String> query) {
        Map<String,Object> result=repository.participations(memberId,query,memberId==null?scope.queryScope():null);
        @SuppressWarnings("unchecked") List<Map<String,Object>> records=(List<Map<String,Object>>)result.get("records");
        if(memberId==null)ComputeDisplayNames.administrators(data,records,Map.of("redeemedBy","redeemedByUsername"));
        records.forEach(ComputeActivityService::publicRecord);
        return result;
    }

    /** 当前主轮盘已持有活动行锁；旧兑换码不改写，碰撞时只重选码，不重抽奖。 */
    private String nextRedemptionCode() {
        for(int attempt=0;attempt<32;attempt++) {
            String code=randomRedemptionCode();
            if(repository.nextRedemptionCodeActivityParticipationCount(code)==0)return code;
        }
        throw new IllegalStateException("兑奖码暂时无法分配，请重试原请求");
    }

    static String randomRedemptionCode() {
        char[] code=new char[6];
        code[0]=CODE_LETTERS.charAt(CODE_RANDOM.nextInt(CODE_LETTERS.length()));
        code[1]=CODE_DIGITS.charAt(CODE_RANDOM.nextInt(CODE_DIGITS.length()));
        String alphabet=CODE_LETTERS+CODE_DIGITS;
        for(int index=2;index<code.length;index++)code[index]=alphabet.charAt(CODE_RANDOM.nextInt(alphabet.length()));
        for(int index=code.length-1;index>0;index--){int chosen=CODE_RANDOM.nextInt(index+1);char c=code[index];code[index]=code[chosen];code[chosen]=c;}
        return new String(code);
    }

    private static Map<String, Object> publicRecord(Map<String, Object> row) {
        row.remove("activitySnapshot");
        if(text(row.get("orderNo")).isEmpty())row.put("orderNo","ACT"+row.get("id"));
        return row;
    }
    private long primaryWheel(){return number(data.setting("wheelActivityId","0"));}
    private void assertCurrentWheel(Map<String,Object> activity){if("WHEEL".equals(activity.get("type"))&&number(activity.get("id"))!=primaryWheel())throw new BusinessException("server.compute.activity.notOpen");}
    private static int limit(Map<String, String> query) { return (int) integer(query.getOrDefault("size", "20"), 1, 100); }
    private static long offset(Map<String, String> query) { return (integer(query.getOrDefault("page", "1"), 1, 100000) - 1) * limit(query); }
    private static long integer(Object value, long minimum, long maximum) { long number = number(value); if (number < minimum || number > maximum) throw invalid(); return number; }
    private static LocalDateTime time(Object value) { try { return OffsetDateTime.parse(text(value)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); } catch (RuntimeException error) { throw new BusinessException("server.compute.activity.invalidTime"); } }
    private static String required(Object value, int max) { String text = limited(value, max); if (text.isEmpty()) throw invalid(); return text; }
    private static String limited(Object value, int max) { String text = text(value); if (text.length() > max) throw invalid(); return text; }
    private static BusinessException invalid() { return new BusinessException("server.compute.activity.invalidInput"); }
    private static List<Map<String, Object>> normalizePrizes(Object input) {
        if (input == null) return List.of();
        if (!(input instanceof List<?> rows) || rows.size() > 50) throw invalid();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row)) throw invalid();
            BigDecimal probability=nonnegative(row.get("probability"));
            if(probability.stripTrailingZeros().scale()>6||probability.compareTo(new BigDecimal("1000000"))>0)throw invalid();
            Map<String,Object> prize=fields("name", required(row.get("name"), 100), "amount", nonnegative(row.get("amount")),
                    "probability", probability, "stock", integer(row.get("stock"), 1, 1_000_000_000L));
            prize.put("is_no_prize",enabled(row.get("isNoPrize")));
            prize.put("auto_credit",enabled(row.get("autoCredit")));
            if(enabled(row.get("isNoPrize"))&&decimal(row.get("amount")).signum()!=0)throw invalid();
            prize.put("min_draw",integer(row.get("minDraw")==null?0:row.get("minDraw"),0,1_000_000_000L));
            prize.put("image_url",ComputeWheelService.image(row.get("imageUrl")));prize.put("display_image_url",ComputeWheelService.image(row.get("displayImageUrl")));
            result.add(prize);
        }
        return result;
    }
}
