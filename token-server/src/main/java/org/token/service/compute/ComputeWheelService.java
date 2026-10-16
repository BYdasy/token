package org.token.service.compute;

import org.token.repository.compute.ComputeWheelRepository;
import org.token.model.query.ActivityDepositQuery;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeActivityRules;
import org.token.service.compute.ComputeContentHtml;
import org.token.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 参考转盘的次数、独立命中与兑奖。库存预算由活动主事务预留，兑奖只使用中奖时金额快照。 */
@Service
public class ComputeWheelService {
    private final ComputeData data;
    private final ComputeWheelRepository repository;
    private final ComputeAgentScope scope;
    private final ComputeBusinessService wallets;
    private final SystemSettingService systemSettings;
    private final ComputeActivityInviteChances inviteChances;
    private static final SecureRandom RANDOM = new SecureRandom();
    public ComputeWheelService(ComputeData data, ComputeAgentScope scope, ComputeBusinessService wallets) {
        this(data,scope,wallets,null);
    }
    public ComputeWheelService(ComputeData data, ComputeAgentScope scope, ComputeBusinessService wallets, SystemSettingService systemSettings) {
        this(data,new ComputeWheelRepository(data),scope,wallets,systemSettings);
    }
    @Autowired
    public ComputeWheelService(ComputeData data,ComputeWheelRepository repository,ComputeAgentScope scope,ComputeBusinessService wallets,SystemSettingService systemSettings) {
        this.data=data;this.repository=repository;this.scope=scope;this.wallets=wallets;this.systemSettings=systemSettings;
        this.inviteChances=new ComputeActivityInviteChances(data);
    }

    public Map<String,Object> config(Map<String,Object> activity) {
        Object raw=activity.get("wheelConfig");
        Map<String,Object> result=new LinkedHashMap<>();
        if(raw instanceof Map<?,?> map)map.forEach((k,v)->result.put(String.valueOf(k),v));
        else if(raw!=null)result.putAll(data.jsonMap(raw));
        if("CHEST".equals(activity.get("type"))&&!result.containsKey("chestRanges")&&activity.get("minReward")!=null)
            result.put("chestRanges",List.of(fields("id","legacy-range","minReward",text(activity.get("minReward")),"maxReward",text(activity.get("maxReward")),"weight","100")));
        return result;
    }
    public boolean reference(Map<String,Object> activity) {return Set.of("WHEEL","CHEST").contains(activity.get("type"))&&"REF".equals(config(activity).get("mode"))&&(!"CHEST".equals(activity.get("type"))||hasTiers(activity));}

    public Map<String,Object> normalize(Object raw, Map<String,Object> previous,String activityType) {
        Map<String,Object> old=previous==null?new LinkedHashMap<>():config(previous);
        if(raw==null)return old;
        if(!(raw instanceof Map<?,?> values))throw invalid();
        String mode=text(values.get("mode"));if(!"REF".equals(mode))throw invalid();

        boolean chest="CHEST".equals(activityType);
        String earning=text(values.get("earningType"));
        if(!(chest?Set.of("SINGLE_DEPOSIT","CUMULATIVE_DEPOSIT"):Set.of("FIRST_DEPOSIT","CUMULATIVE_DEPOSIT","INVITED_MEMBERS")).contains(earning))throw invalid();
        List<Map<String,Object>> tiers=normalizeTiers(values.get("tiers"));
        if(chest) {
            if(tiers.isEmpty())throw invalid();
            for(Map<String,Object> tier:tiers){if(number(tier.get("selfCount"))<=0)throw invalid();tier.put("inviterCount",0L);}
        }
        if("INVITED_MEMBERS".equals(earning)) {
            if(tiers.isEmpty())throw invalid();
            for(Map<String,Object> tier:tiers) {
                BigDecimal members=decimal(tier.get("threshold"));
                if(members.stripTrailingZeros().scale()>0||members.compareTo(BigDecimal.valueOf(1_000_000))>0||number(tier.get("selfCount"))<=0)throw invalid();
                tier.put("threshold",members.stripTrailingZeros().toPlainString());
                tier.put("inviterCount",0L);
            }
        }
        BigDecimal threshold=tiers.isEmpty()?ComputeActivityRules.nonnegative(values.get("threshold")):decimal(tiers.get(0).get("threshold"));if(threshold.signum()<=0)throw invalid();
        List<Long> groups=new ArrayList<>();Object groupsRaw=values.get("groupIds");
        if(groupsRaw instanceof List<?> ids)for(Object id:ids){long value=number(id);if(value<0||groups.size()>=200)throw invalid();if(!groups.contains(value))groups.add(value);}
        if(enabled(values.get("controlByGroup"))&&groups.isEmpty())throw invalid();
        for(long group:groups)if(group!=0&&repository.normalizeMemberGroupCount(group)==0)throw invalid();
        String description=text(values.get("descriptionHtml"));
        // 历史即时奖励模式没有转盘说明；新参考模式仍必须提供安全且非空的正文。
        String safeDescription=description.isBlank()?"":ComputeContentHtml.sanitize(description);
        Map<String,String> descriptions=new LinkedHashMap<>();
        Object translated=values.containsKey("descriptionTranslations")?values.get("descriptionTranslations"):old.get("descriptionTranslations");
        if(translated!=null) {
            if(!(translated instanceof Map<?,?> translations)||translations.size()>100)throw invalid();
            for(var entry:translations.entrySet()) {
                String language=text(entry.getKey()).replace('_','-').toLowerCase(Locale.ROOT);
                if(!language.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*"))throw invalid();
                String html=text(entry.getValue());
                if(!html.isBlank())descriptions.put(language,ComputeContentHtml.sanitize(html));
            }
        }
        if(!descriptions.isEmpty()&&!descriptions.containsKey("en"))throw invalid();
        long duration=number(values.containsKey("spinDurationSeconds")?values.get("spinDurationSeconds"):old.getOrDefault("spinDurationSeconds",6));
        if(duration<3||duration>30)throw invalid();
        Map<String,Object> translations=new LinkedHashMap<>();
        Object rawTranslations=values.containsKey("translations")?values.get("translations"):old.get("translations");
        if(rawTranslations!=null) {
            if(!(rawTranslations instanceof Map<?,?> entries)||entries.size()>100)throw invalid();
            for(var entry:entries.entrySet()) {
                String language=text(entry.getKey()).replace('_','-').toLowerCase(Locale.ROOT);
                if(!language.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")||!(entry.getValue() instanceof Map<?,?> item))throw invalid();
                String title=text(item.get("title")),summary=text(item.get("description")),html=text(item.get("content"));
                if(title.length()>160||summary.length()>10000)throw invalid();
                translations.put(language,fields("title",title,"description",summary,"content",html.isBlank()?"":ComputeContentHtml.sanitize(html)));
            }
        }
        Map<String,Object> normalized=fields("tiers",tiers,"translations",translations,"mode",mode,"earningType",earning,"threshold",threshold.toPlainString(),"systemControl",enabled(values.get("systemControl")),
                "controlByGroup",enabled(values.get("controlByGroup")),"groupIds",groups,"imageUrl",image(values.get("imageUrl")),
                "topImageUrl",image(values.get("topImageUrl")),"descriptionHtml",safeDescription,
                "descriptionTranslations",descriptions,"spinDurationSeconds",duration,
                "limitsMode","ATTEMPTS",
                "round",number(old.getOrDefault("round",1)),"roundStartedAt",old.getOrDefault("roundStartedAt",now().atOffset(ZoneOffset.UTC).toString()));
        long bottom=number(values.containsKey("floatingBottom")?values.get("floatingBottom"):old.getOrDefault("floatingBottom",160));
        if(bottom<0||bottom>2000)throw invalid();
        normalized.put("floatingBottom",bottom);
        normalized.put("bannerImage",image(values.containsKey("bannerImage")?values.get("bannerImage"):old.get("bannerImage")));
        BigDecimal firstDeposit=ComputeActivityRules.nonnegative(values.containsKey("minimumFirstDeposit")?values.get("minimumFirstDeposit"):old.getOrDefault("minimumFirstDeposit","10"));
        if(firstDeposit.signum()<=0)throw invalid();
        normalized.put("minimumFirstDeposit",firstDeposit.toPlainString());
        String cycle=text(values.containsKey("resetCycle")?values.get("resetCycle"):old.getOrDefault("resetCycle","NONE"));
        if(!Set.of("NONE","DAY","WEEK","MONTH").contains(cycle))throw invalid();
        normalized.put("resetCycle",cycle);
        if(!"NONE".equals(cycle)) {
            Object began=cycle.equals(old.get("resetCycle"))?old.get("resetCycleStartedAt"):null;
            normalized.put("resetCycleStartedAt",began==null?cycleStart(cycle,now()).atOffset(ZoneOffset.UTC).toString():began);
        }
        if(chest) {
            List<Map<String,Object>> ranges=normalizeChestRanges(values.containsKey("chestRanges")?values.get("chestRanges"):old.get("chestRanges"));
            if(previous!=null)assertChestTargetsUnchanged(previous,ranges);
            normalized.put("chestRanges",ranges);
            Object cutoff=old.get("chestQualificationStartedAt");
            // 切换计次方式不重算旧订单；截止点由服务端固定，不能由客户端回拨重复领取。
            if(previous!=null&&!earning.equals(old.get("earningType")))cutoff=now().atOffset(ZoneOffset.UTC).toString();
            if(cutoff!=null)normalized.put("chestQualificationStartedAt",cutoff);
        }
        return normalized;
    }

    static List<Map<String,Object>> normalizeChestRanges(Object raw) {
        if(!(raw instanceof List<?> values)||values.isEmpty()||values.size()>100)throw invalid();
        List<Map<String,Object>> ranges=new ArrayList<>();Set<String> ids=new HashSet<>();boolean positive=false;
        for(Object value:values) {
            if(!(value instanceof Map<?,?> range))throw invalid();
            String id=text(range.get("id"));if(!id.matches("[A-Za-z0-9_-]{1,80}")||!ids.add(id))throw invalid();
            BigDecimal min=ComputeActivityRules.nonnegative(range.get("minReward")),max=ComputeActivityRules.nonnegative(range.get("maxReward"));
            BigDecimal weight=ComputeActivityRules.nonnegative(range.get("weight")).stripTrailingZeros();
            if(min.signum()<=0||max.compareTo(min)<0||weight.scale()>6||weight.compareTo(BigDecimal.valueOf(1_000_000))>0)throw invalid();
            positive|=weight.signum()>0;
            ranges.add(fields("id",id,"minReward",min.toPlainString(),"maxReward",max.toPlainString(),"weight",weight.toPlainString()));
        }
        if(!positive)throw invalid();return ranges;
    }
    private void assertChestTargetsUnchanged(Map<String,Object> activity,List<Map<String,Object>> next) {
        long round=number(config(activity).getOrDefault("round",1));
        List<Map<String,Object>> targets=repository.assertChestTargetsUnchangedWheelMemberRows(activity.get("id"), round);
        if(targets.isEmpty())return;
        List<Map<String,Object>> before=normalizeChestRanges(config(activity).get("chestRanges"));
        for(Map<String,Object> target:targets) {
            String id=text(target.get("targetRangeId"));
            Map<String,Object> previous=before.stream().filter(r->id.equals(r.get("id"))).findFirst().orElse(null);
            Map<String,Object> updated=next.stream().filter(r->id.equals(r.get("id"))).findFirst().orElse(null);
            if(previous==null||updated==null||decimal(updated.get("weight")).signum()<=0||decimal(previous.get("minReward")).compareTo(decimal(updated.get("minReward")))!=0||decimal(previous.get("maxReward")).compareTo(decimal(updated.get("maxReward")))!=0)
                throw new BusinessException("server.compute.activity.rangeInUse");
        }
    }

    /** 只在已经锁住会员和活动的事务中同步资格；已审核充值按币种计量，不换算。 */
    private Map<String,Object> sync(long memberId,Map<String,Object> activity) {
        refreshCycle(activity);
        long id=number(activity.get("id"));Map<String,Object> cfg=config(activity);long round=number(cfg.getOrDefault("round",1));
        repository.syncWheelMemberWrite(id, memberId, round, now(), now(), id, memberId, round);
        Map<String,Object> member=repository.syncWheelMemberRow(id, memberId, round);
        if("CHEST".equals(activity.get("type"))&&member.get("chestEarnedOffset")==null) {
            long recorded=recordedChestGrants(id,round,memberId);
            repository.syncWheelMemberWrite2(number(member.get("earnedCount"))-recorded, id, memberId, round);
        }
        List<Map<String,Object>> candidates=null;
        if(hasTiers(activity)) {
            // 已持有会员和活动锁；本次同步的来源资格只查询一次，发放与计数使用同一批来源。
            candidates=tierCandidates(memberId,activity);
            syncTierGrants(memberId,activity,number(member.get("earnedCount")),candidates);
            if("WHEEL".equals(activity.get("type")))inviteChances.sync(memberId,activity,cfg);
        }
        long earned=candidates==null?earned(memberId,activity):tierTotal(memberId,activity,candidates);
        long additional=Math.max(0,earned-number(member.get("earnedCount")));
        boolean automatic=enabled(cfg.get("systemControl"))&&"UNIFIED".equals(member.get("controlType"));
        long release=automatic?number(member.get("pendingCount"))+additional:0;
        repository.syncWheelMemberWrite3(earned, additional, release, release, release, now(), id, memberId, round);
        Map<String,Object> result=repository.syncWheelMemberRow2(id, memberId, round);
        if(hasTiers(activity)) {
            result.put("selfEarnedCount",grantCount(id,round,memberId,"SELF"));
            result.put("inviterEarnedCount",grantCount(id,round,memberId,"INVITER"));
            if("WHEEL".equals(activity.get("type")))result.put("invitedMembersEarnedCount",inviteChances.total(memberId,activity,cfg));
        }
        return result;
    }

    private long earned(long memberId,Map<String,Object> activity) {
        if(hasTiers(activity))return tierTotal(memberId,activity);
        Map<String,Object> cfg=config(activity);
        BigDecimal threshold=new BigDecimal(text(cfg.get("threshold")));
        LocalDateTime start=OffsetDateTime.parse(text(cfg.get("roundStartedAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime begins=OffsetDateTime.parse(text(activity.get("startsAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        if(start.isBefore(begins))start=begins;
        String activityUnit=text(activity.get("currency"));
        if(Set.of("USD","USDT","USDC").contains(activityUnit))activityUnit=ComputeUsdAccounting.WALLET_CURRENCY;
        LocalDateTime ends=OffsetDateTime.parse(text(activity.get("endsAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var query=new ActivityDepositQuery(memberId,activityUnit,start,ends,new ComputeTestMemberParticipation(data).since());
        // 首充仍按全历史成功时间判定，空审核时间沿用创建时间。
        BigDecimal earned=repository.earned(query,threshold,"FIRST_DEPOSIT".equals(cfg.get("earningType")));
        return earned==null?0:earned.min(BigDecimal.valueOf(1_000_000_000L)).longValueExact();
    }

    @Transactional
    public Map<String,Object> memberStatus(long memberId,long activityId) {
        repository.memberStatusMemberRow(memberId);
        Map<String,Object> activity=repository.memberStatusActivityRow(activityId);
        return reference(activity)?sync(memberId,activity):Map.of();
    }

    public Map<String,Object> choose(long memberId,Map<String,Object> activity,List<Map<String,Object>> prizes,BigDecimal budget) {
        Map<String,Object> cfg=config(activity);
        Map<String,Object> member=requireAttempts(memberId,activity);
        long draw=number(member.get("usedCount"))+1;
        List<Map<String,Object>> available=prizes.stream().filter(prize->probability(prize).signum()>0&&number(prize.get("stock"))>number(prize.get("usedStock"))&&(budget==null||decimal(prize.get("amount")).compareTo(budget)<=0)).toList();
        Map<String,Object> selected=null;
        if("INDEPENDENT".equals(member.get("controlType"))&&draw==number(member.get("targetDraw"))) {
            selected=available.stream().filter(p->number(p.get("id"))==number(member.get("targetPrizeId"))).findFirst().orElse(null);
            if(selected==null)throw error("designatedUnavailable");
        }
        if(selected==null)selected=available.stream().filter(p->number(p.get("minDraw"))>0&&draw==number(p.get("minDraw"))).findFirst().orElse(null);
        if(selected==null) {
            List<Map<String,Object>> choices=available.stream().filter(p->draw>number(p.get("minDraw"))&&probability(p).signum()>0).toList();
            BigInteger total=choices.stream().map(p->probability(p).movePointRight(6).toBigIntegerExact()).reduce(BigInteger.ZERO,BigInteger::add);
            if(total.signum()<=0)throw error("designatedUnavailable");
            BigInteger sample;do{sample=new BigInteger(total.bitLength(),RANDOM);}while(sample.compareTo(total)>=0);
            for(Map<String,Object> prize:choices){sample=sample.subtract(probability(prize).movePointRight(6).toBigIntegerExact());if(sample.signum()<0){selected=prize;break;}}
        }
        consume(memberId,activity);
        return Objects.requireNonNull(selected);
    }

    /** 必须由持有会员和活动行锁的参与事务调用。 */
    public Map<String,Object> chooseChest(long memberId,Map<String,Object> activity) {
        Map<String,Object> member=requireAttempts(memberId,activity);
        List<Map<String,Object>> ranges=normalizeChestRanges(config(activity).get("chestRanges"));
        Map<String,Object> selected=null;
        if("INDEPENDENT".equals(member.get("controlType"))&&number(member.get("usedCount"))+1==number(member.get("targetDraw"))) {
            selected=ranges.stream().filter(r->text(r.get("id")).equals(text(member.get("targetRangeId")))&&decimal(r.get("weight")).signum()>0).findFirst().orElse(null);
            if(selected==null)throw error("designatedUnavailable");
        }
        if(selected==null)selected=chooseChestRange(ranges);
        consume(memberId,activity);return selected;
    }
    static Map<String,Object> chooseChestRange(List<Map<String,Object>> ranges) {
        BigInteger total=ranges.stream().map(r->decimal(r.get("weight")).movePointRight(6).toBigIntegerExact()).reduce(BigInteger.ZERO,BigInteger::add);
        if(total.signum()<=0)throw error("designatedUnavailable");
        BigInteger sample;do{sample=new BigInteger(total.bitLength(),RANDOM);}while(sample.compareTo(total)>=0);
        return chestRangeAt(ranges,sample);
    }
    /** 按累计精确权重落点选区间；零权重永远不占抽样空间。 */
    static Map<String,Object> chestRangeAt(List<Map<String,Object>> ranges,BigInteger sample) {
        if(sample.signum()<0)throw invalid();
        for(Map<String,Object> range:ranges){sample=sample.subtract(decimal(range.get("weight")).movePointRight(6).toBigIntegerExact());if(sample.signum()<0)return range;}
        throw invalid();
    }
    private Map<String,Object> requireAttempts(long memberId,Map<String,Object> activity) {
        Map<String,Object> cfg=config(activity);
        if(enabled(cfg.get("controlByGroup"))) {
            Object group=repository.requireAttemptsMemberRow(memberId).get("groupId");
            List<?> groups=(List<?>)cfg.get("groupIds");
            if(groups.stream().noneMatch(value->number(value)==(group==null?0:number(group))))throw error("groupRequired");
        }
        Map<String,Object> member=sync(memberId,activity);
        if(number(member.get("availableCount"))<=0)throw error("noAttempts");
        return member;
    }
    private void consume(long memberId,Map<String,Object> activity) {
        if(repository.consumeWheelMemberWrite(now(), activity.get("id"), memberId, config(activity).getOrDefault("round",1))!=1)throw error("noAttempts");
    }

    @Transactional
    public Map<String,Object> adjust(long activityId,long memberId,Map<String,Object> request,int operator) {
        scope.relations().lock();scope.assertMember(memberId);
        repository.memberStatusMemberRow(memberId);
        Map<String,Object> activity=repository.memberStatusActivityRow(activityId);if(!reference(activity))throw invalid();
        String requestId=text(request.get("requestId"));if(!requestId.matches("[A-Za-z0-9_-]{8,80}"))throw invalid();
        List<Map<String,Object>> prior=repository.adjustWheelAdjustmentRows(activityId, memberId, requestId);
        long add=bounded(request.get("addCount")),sub=bounded(request.get("subtractCount"));
        String digest=payloadHash(request);
        if(!prior.isEmpty()){Map<String,Object> row=prior.get(0);if(!digest.equals(row.get("payloadHash")))throw new BusinessException("server.compute.requestConflict");return row;}
        Map<String,Object> member=sync(memberId,activity);String control=text(request.get("controlType"));if(!Set.of("UNIFIED","INDEPENDENT").contains(control))throw invalid();
        if(!"NONE".equals(config(activity).getOrDefault("resetCycle","NONE"))&&!request.containsKey("roundNo"))throw new BusinessException("server.compute.requestConflict");
        if(request.containsKey("roundNo")&&number(request.get("roundNo"))!=number(member.get("roundNo")))throw new BusinessException("server.compute.requestConflict");
        long targetDraw=bounded(request.get("targetDraw")),targetPrize=bounded(request.get("targetPrizeId"));String targetRange=text(request.get("targetRangeId"));
        boolean chest="CHEST".equals(activity.get("type"));
        if(chest) {
            if(targetPrize!=0||(!targetRange.isBlank()&&!targetRange.matches("[A-Za-z0-9_-]{1,80}")))throw invalid();
            if("INDEPENDENT".equals(control)&&(targetDraw>0||!targetRange.isBlank())&&(targetDraw<=number(member.get("usedCount"))||normalizeChestRanges(config(activity).get("chestRanges")).stream().noneMatch(r->targetRange.equals(r.get("id"))&&decimal(r.get("weight")).signum()>0)))throw invalid();
        } else {
            if(!targetRange.isBlank())throw invalid();
            if("INDEPENDENT".equals(control)&&(targetDraw>0||targetPrize>0)&&(targetDraw<=number(member.get("usedCount"))||repository.adjustActivityPrizeCount(activityId, targetPrize)==0))throw invalid();
        }
        long before=number(member.get("availableCount")),after=before+add-sub;if(after<0||after>1_000_000_000L)throw error("invalidAttempts");
        String remark=text(request.get("remark"));if(remark.isEmpty()||remark.length()>500)throw invalid();
        repository.adjustWheelMemberWrite(after, add, add, control, targetDraw, targetPrize==0?null:targetPrize, chest&&!targetRange.isBlank()?targetRange:null, now(), activityId, memberId, member.get("roundNo"));
        long id=data.insert("compute_wheel_adjustment",fields("activity_id",activityId,"member_id",memberId,"round_no",member.get("roundNo"),"request_id",requestId,"payload_hash",digest,"added_count",add,"subtracted_count",sub,"before_count",before,"after_count",after,"operator_id",operator,"remark",remark,"created_at",now()));
        return repository.adjustWheelAdjustmentRow(id);
    }

    @Transactional
    public Map<String,Object> redeem(long id,String mode,int operator) {
        if(!Set.of("AUTO","MANUAL").contains(mode))throw invalid();
        Map<String,Object> first=repository.redeemActivityParticipationRow(id);
        long memberId=number(first.get("memberId"));scope.relations().lock();scope.assertMember(memberId);
        repository.redeemMemberRow(memberId);
        repository.redeemActivityRow(first.get("activityId"));
        Map<String,Object> row=repository.redeemActivityParticipationRow2(id);
        row.remove("activitySnapshot");
        if(!"PENDING_REDEMPTION".equals(row.get("status"))) {
            if(mode.equals(row.get("redemptionType")))return row;
            throw error("alreadyRedeemed");
        }
        if("AUTO".equals(mode)&&decimal(row.get("amount")).signum()>0)wallets.creditActivityReward(memberId,text(row.get("currency")),decimal(row.get("amount")),id);
        repository.redeemActivityParticipationWrite("AUTO".equals(mode)?"PAID":"MANUAL_REDEEMED", mode, now(), operator, id);
        row=repository.redeemActivityParticipationRow(id);row.remove("activitySnapshot");return row;
    }

    @Transactional
    public void reset(long activityId) {
        scope.requireGlobalScope();Map<String,Object> activity=repository.memberStatusActivityRow(activityId);
        if(enabled(activity.get("enabled"))||!reference(activity))throw error("disableBeforeReset");
        Map<String,Object> cfg=config(activity);cfg.put("round",number(cfg.getOrDefault("round",1))+1);cfg.put("roundStartedAt",now().atOffset(ZoneOffset.UTC).toString());
        String cycle=text(cfg.getOrDefault("resetCycle","NONE"));
        if(!"NONE".equals(cycle))cfg.put("resetCycleStartedAt",cycleStart(cycle,now()).atOffset(ZoneOffset.UTC).toString());
        repository.resetActivityWrite(data.json(cfg), now(), activityId);
    }

    /** 调用者已经持有活动行锁。换轮使旧次数和独立指定同时失效，历史凭据和到账记录不删除。 */
    void refreshCycle(Map<String,Object> activity) { refreshCycle(activity,now()); }

    void refreshCycle(Map<String,Object> activity,LocalDateTime current) {
        if(!Set.of("WHEEL","CHEST").contains(activity.get("type")))return;
        Map<String,Object> cfg=config(activity);String cycle=text(cfg.getOrDefault("resetCycle","NONE"));
        if("NONE".equals(cycle))return;
        LocalDateTime starts=OffsetDateTime.parse(text(activity.get("startsAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime ends=OffsetDateTime.parse(text(activity.get("endsAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        if(current.isBefore(starts)||!current.isBefore(ends))return;
        LocalDateTime begin=cycleStart(cycle,current);
        Object previous=cfg.get("resetCycleStartedAt");
        if(previous!=null) {
            LocalDateTime last=OffsetDateTime.parse(text(previous)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            if(!begin.isAfter(last))return;
            cfg.put("round",Math.addExact(number(cfg.getOrDefault("round",1)),1));
            cfg.put("roundStartedAt",begin.atOffset(ZoneOffset.UTC).toString());
        }
        // 没有周期基线的配置首次只固定边界；不能因部署新字段立刻清空旧次数。
        cfg.put("resetCycleStartedAt",begin.atOffset(ZoneOffset.UTC).toString());
        repository.resetActivityWrite(data.json(cfg), current, activity.get("id"));
        activity.put("wheelConfig",cfg);
    }

    private LocalDateTime cycleStart(String cycle,LocalDateTime current) {
        return ComputeActivityCycle.start(cycle,current,ZoneId.of(systemSettings==null?"UTC":systemSettings.resolveSystemTimeZone()));
    }

    @Transactional
    public Map<String,Object> members(long activityId,Map<String,String> query) {
        Map<String,Object> activity=repository.memberStatusActivityRow(activityId);if(!reference(activity))return Map.of("records",List.of(),"total",0);
        refreshCycle(activity);
        var page=repository.members(activityId,number(config(activity).getOrDefault("round",1)),query,scope.queryScope());
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows=(List<Map<String,Object>>)page.get("records");
        long total=number(page.get("total"));
        // LEFT JOIN 的空会员主键由独立别名回填，避免重复列覆盖。
        rows.forEach(row->{
            row.put("memberId",row.remove("listedMemberId"));
            for(String key:List.of("availableCount","issuedCount","usedCount","pendingCount","earnedCount","targetDraw"))row.putIfAbsent(key,0);
            row.putIfAbsent("controlType","UNIFIED");
            row.putIfAbsent("roundNo",number(config(activity).getOrDefault("round",1)));
            long extra=Math.max(0,earned(number(row.get("memberId")),activity)-number(row.get("earnedCount")));
            long pending=number(row.get("pendingCount"))+extra;
            boolean automatic=enabled(config(activity).get("systemControl"))&&"UNIFIED".equals(row.get("controlType"));
            row.put("earnedCount",number(row.get("earnedCount"))+extra);row.put("pendingCount",automatic?0:pending);
            if(automatic){row.put("availableCount",number(row.get("availableCount"))+pending);row.put("issuedCount",number(row.get("issuedCount"))+pending);}
        });
        List<Map<String,Object>> choices;
        if("CHEST".equals(activity.get("type"))) {
            choices=new ArrayList<>();for(Map<String,Object> range:normalizeChestRanges(config(activity).get("chestRanges"))) {
                Map<String,Object> choice=new LinkedHashMap<>(range);choice.put("name",decimal(range.get("minReward")).stripTrailingZeros().toPlainString()+"–"+decimal(range.get("maxReward")).stripTrailingZeros().toPlainString());choices.add(choice);
            }
        } else choices=repository.membersActivityPrizeRows(activityId);
        return Map.of("records",rows,"total",total,"prizes",choices,"currentRound",number(config(activity).getOrDefault("round",1)));
    }

    public Map<String,Object> statistics(Map<String,String> query) {
        return repository.statistics(query,scope.queryScope());
    }

    static List<Map<String,Object>> normalizeTiers(Object input) {
        if(input==null)return List.of();
        if(!(input instanceof List<?> values)||values.size()>100)throw invalid();
        List<Map<String,Object>> result=new ArrayList<>();Set<BigDecimal> thresholds=new HashSet<>();
        for(Object value:values) {
            if(!(value instanceof Map<?,?> tier))throw invalid();
            BigDecimal threshold=ComputeActivityRules.nonnegative(tier.get("threshold"));
            long self=bounded(tier.get("selfCount")),inviter=bounded(tier.get("inviterCount"));
            if(threshold.signum()<=0||!thresholds.add(threshold)||self+inviter==0||self>1000000||inviter>1000000)throw invalid();
            result.add(fields("threshold",threshold.toPlainString(),"selfCount",self,"inviterCount",inviter));
        }
        result.sort(Comparator.comparing(row->decimal(row.get("threshold"))));return result;
    }
    private boolean hasTiers(Map<String,Object> activity) {return config(activity).get("tiers") instanceof List<?> tiers&&!tiers.isEmpty();}
    private List<Map<String,Object>> tierCandidates(long memberId,Map<String,Object> activity) {
        if("CHEST".equals(activity.get("type")))return chestTierCandidates(memberId,activity);
        Map<String,Object> cfg=config(activity);
        if("INVITED_MEMBERS".equals(cfg.get("earningType")))return List.of();
        LocalDateTime start=OffsetDateTime.parse(text(activity.get("startsAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime round=OffsetDateTime.parse(text(cfg.get("roundStartedAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        // 保存旧活动升级规则不改变活动起点；显式重置轮次才启用新的轮次起点。
        if(number(cfg.getOrDefault("round",1))>1&&round.isAfter(start))start=round;
        LocalDateTime end=OffsetDateTime.parse(text(activity.get("endsAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        boolean first="FIRST_DEPOSIT".equals(cfg.get("earningType"));
        var query=new ActivityDepositQuery(memberId,ComputeUsdAccounting.WALLET_CURRENCY,start,end,new ComputeTestMemberParticipation(data).since());
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> source:repository.wheelTiers(query,first)) {
            BigDecimal deposited=decimal(source.get("amount"));
            List<Map<String,Object>> qualifying=normalizeTiers(cfg.get("tiers")).stream().filter(tier->deposited.compareTo(decimal(tier.get("threshold")))>=0).toList();
            if(first&&!qualifying.isEmpty())qualifying=List.of(qualifying.get(qualifying.size()-1));
            for(Map<String,Object> tier:qualifying) {
                String role=number(source.get("memberId"))==memberId?"SELF":"INVITER";
                long count=number(tier.get("SELF".equals(role)?"selfCount":"inviterCount"));
                if(count>0)result.add(fields("sourceMemberId",source.get("memberId"),"beneficiaryRole",role,"threshold",tier.get("threshold"),"awardedCount",count,"depositAmount",deposited));
            }
        }
        return result;
    }
    private void syncTierGrants(long memberId,Map<String,Object> activity,long oldEarned,List<Map<String,Object>> candidates) {
        long id=number(activity.get("id")),round=number(config(activity).getOrDefault("round",1));
        if(repository.syncTierGrantsActivityAttemptGrantCount(id, round, memberId)==0)
            data.insert("compute_activity_attempt_grant",fields("activity_id",id,"round_no",round,"source_member_id",memberId,"beneficiary_member_id",memberId,"beneficiary_role","LEGACY","threshold_amount",BigDecimal.ZERO,"awarded_count",oldEarned,"deposit_amount",BigDecimal.ZERO,"created_at",now()));
        boolean chest="CHEST".equals(activity.get("type")),single=chest&&"SINGLE_DEPOSIT".equals(config(activity).get("earningType")),first="FIRST_DEPOSIT".equals(config(activity).get("earningType"));
        List<Map<String,Object>> prior=repository.priorGrants(id,round,memberId,chest);
        Set<String> seen=new HashSet<>();for(Map<String,Object> grant:prior)seen.add(grantKey(grant.get("sourceMemberId"),grant.get("beneficiaryRole"),grant.getOrDefault("sourceDepositId",0L),grant.get("thresholdAmount"),first||single));
        for(Map<String,Object> candidate:candidates) {
            String key=grantKey(candidate.get("sourceMemberId"),candidate.get("beneficiaryRole"),candidate.getOrDefault("sourceDepositId",0L),candidate.get("threshold"),first||single);
            if(!seen.add(key))continue;
            data.insert("compute_activity_attempt_grant",fields("activity_id",id,"round_no",round,"source_member_id",candidate.get("sourceMemberId"),"beneficiary_member_id",memberId,"beneficiary_role",candidate.get("beneficiaryRole"),"threshold_amount",candidate.get("threshold"),"source_deposit_id",candidate.getOrDefault("sourceDepositId",0L),"awarded_count",candidate.get("awardedCount"),"deposit_amount",candidate.get("depositAmount"),"created_at",now()));
        }
    }
    private static String grantKey(Object source,Object role,Object deposit,Object threshold,boolean once) {
        return number(source)+"/"+text(role)+"/"+number(deposit)+(once?"":"/"+decimal(threshold).stripTrailingZeros().toPlainString());
    }
    private long grantCount(long id,long round,long member,String role) {
        return repository.grantCountActivityAttemptGrantValue(id, round, member, role);
    }
    private long tierTotal(long memberId,Map<String,Object> activity) {
        return tierTotal(memberId,activity,tierCandidates(memberId,activity));
    }
    private long tierTotal(long memberId,Map<String,Object> activity,List<Map<String,Object>> candidates) {
        if("CHEST".equals(activity.get("type")))return chestTierTotal(memberId,activity,candidates);
        long id=number(activity.get("id")),round=number(config(activity).getOrDefault("round",1));
        List<Map<String,Object>> grants=repository.tierTotalActivityAttemptGrantRows(id, round, memberId, memberId);
        long baseline=grants.stream().filter(g->"LEGACY".equals(g.get("beneficiaryRole"))&&number(g.get("beneficiaryMemberId"))==memberId).mapToLong(g->number(g.get("awardedCount"))).sum();
        boolean initialized=grants.stream().anyMatch(g->"LEGACY".equals(g.get("beneficiaryRole"))&&number(g.get("beneficiaryMemberId"))==memberId);
        if(!initialized)baseline=repository.tierTotalWheelMemberValue(id, round, memberId);
        long self=0,inviter=0;
        for(Map<String,Object> grant:grants)if(number(grant.get("beneficiaryMemberId"))==memberId) {
            if("SELF".equals(grant.get("beneficiaryRole")))self+=number(grant.get("awardedCount"));
            if("INVITER".equals(grant.get("beneficiaryRole")))inviter+=number(grant.get("awardedCount"));
        }
        for(Map<String,Object> candidate:candidates) {
            boolean seen=grants.stream().anyMatch(g->number(g.get("sourceMemberId"))==number(candidate.get("sourceMemberId"))&&g.get("beneficiaryRole").equals(candidate.get("beneficiaryRole"))&&("FIRST_DEPOSIT".equals(config(activity).get("earningType"))||decimal(g.get("thresholdAmount")).compareTo(decimal(candidate.get("threshold")))==0));
            if(!seen) {if("SELF".equals(candidate.get("beneficiaryRole")))self+=number(candidate.get("awardedCount"));else inviter+=number(candidate.get("awardedCount"));}
        }
        long result=Math.addExact(Math.addExact(self,Math.max(baseline,inviter)),inviteChances.total(memberId,activity,config(activity)));
        if(result>1_000_000_000L)throw error("invalidAttempts");
        return result;
    }

    private List<Map<String,Object>> chestTierCandidates(long memberId,Map<String,Object> activity) {
        Map<String,Object> cfg=config(activity);String earning=text(cfg.get("earningType"));
        LocalDateTime start=OffsetDateTime.parse(text(activity.get("startsAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        if(number(cfg.getOrDefault("round",1))>1) {
            LocalDateTime round=OffsetDateTime.parse(text(cfg.get("roundStartedAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();if(round.isAfter(start))start=round;
        }
        if(cfg.get("chestQualificationStartedAt")!=null) {
            LocalDateTime cut=OffsetDateTime.parse(text(cfg.get("chestQualificationStartedAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();if(cut.isAfter(start))start=cut;
        }
        LocalDateTime end=OffsetDateTime.parse(text(activity.get("endsAt"))).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        boolean single="SINGLE_DEPOSIT".equals(earning),first="FIRST_DEPOSIT".equals(earning);
        var query=new ActivityDepositQuery(memberId,ComputeUsdAccounting.WALLET_CURRENCY,start,end,new ComputeTestMemberParticipation(data).since());
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> source:repository.chestTiers(query,single,first)) {
            BigDecimal deposited=decimal(source.get("amount"));
            List<Map<String,Object>> matched=normalizeTiers(cfg.get("tiers")).stream().filter(t->deposited.compareTo(decimal(t.get("threshold")))>=0).toList();
            if((single||first)&&!matched.isEmpty())matched=List.of(matched.get(matched.size()-1));
            for(Map<String,Object> tier:matched)if(number(tier.get("selfCount"))>0)
                result.add(fields("sourceMemberId",memberId,"sourceDepositId",source.get("sourceDepositId"),"beneficiaryRole","SELF","threshold",tier.get("threshold"),"awardedCount",tier.get("selfCount"),"depositAmount",deposited));
        }
        return result;
    }
    private long recordedChestGrants(long id,long round,long memberId) {
        return repository.recordedChestGrantsActivityAttemptGrantValue(id, round, memberId);
    }
    private long chestTierTotal(long memberId,Map<String,Object> activity,List<Map<String,Object>> candidates) {
        long id=number(activity.get("id")),round=number(config(activity).getOrDefault("round",1));
        List<Map<String,Object>> grants=repository.chestTierTotalActivityAttemptGrantRows(id, round, memberId);
        long persisted=grants.stream().mapToLong(g->number(g.get("awardedCount"))).sum();
        List<Map<String,Object>> members=repository.chestTierTotalWheelMemberRows(id, round, memberId);
        long offset=members.isEmpty()?0:members.get(0).get("chestEarnedOffset")==null?number(members.get(0).get("earnedCount"))-persisted:number(members.get(0).get("chestEarnedOffset"));
        String earning=text(config(activity).get("earningType"));boolean single="SINGLE_DEPOSIT".equals(earning),first="FIRST_DEPOSIT".equals(earning);
        long total=Math.addExact(offset,persisted);Set<String> seen=new HashSet<>();
        for(Map<String,Object> grant:grants)seen.add(grantKey(grant.get("sourceMemberId"),grant.get("beneficiaryRole"),grant.getOrDefault("sourceDepositId",0L),grant.get("thresholdAmount"),first||single));
        for(Map<String,Object> candidate:candidates) {
            String key=grantKey(candidate.get("sourceMemberId"),candidate.get("beneficiaryRole"),candidate.get("sourceDepositId"),candidate.get("threshold"),first||single);
            if(seen.add(key))total=Math.addExact(total,number(candidate.get("awardedCount")));
        }
        if(total<0||total>1_000_000_000L)throw error("invalidAttempts");return total;
    }

    public static BigDecimal probability(Map<String,Object> prize) {return decimal(prize.get("probability"));}
    public static String image(Object value) {String image=text(value);if(image.length()>500)throw invalid();return ComputeContentHtml.safeUrl(image);}
    private String payloadHash(Map<String,Object> request) {
        Map<String,Object> payload=fields("add",bounded(request.get("addCount")),"subtract",bounded(request.get("subtractCount")),"control",text(request.get("controlType")),"draw",bounded(request.get("targetDraw")),"prize",bounded(request.get("targetPrizeId")),"remark",text(request.get("remark")));
        if(!text(request.get("targetRangeId")).isBlank())payload.put("range",text(request.get("targetRangeId")));
        if(request.containsKey("roundNo"))payload.put("round",number(request.get("roundNo")));
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data.json(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("当前JDK不支持SHA-256",error);}
    }
    private static long bounded(Object value){long n=value==null?0:number(value);if(n<0||n>1_000_000_000L)throw invalid();return n;}
    private static BusinessException invalid(){return new BusinessException("server.compute.activity.invalidInput");}
    private static BusinessException error(String key){return new BusinessException("server.compute.v6."+key);}
}
