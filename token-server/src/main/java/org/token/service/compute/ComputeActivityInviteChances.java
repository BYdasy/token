package org.token.service.compute;

import org.token.repository.compute.ComputeActivityInviteChancesRepository;
import org.token.model.query.ActivityDepositQuery;

import org.token.repository.compute.ComputeData;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 直属有效成员阶梯单独留存次数凭据，不能和本人充值门槛或旧邀请奖励混用。 */
final class ComputeActivityInviteChances {
    private final ComputeData data;
    private final ComputeActivityInviteChancesRepository repository;
    ComputeActivityInviteChances(ComputeData data) { this.data=data;
        this.repository = new ComputeActivityInviteChancesRepository(data);
    }

    /** 由持有会员、活动行锁的事务调用；每一人数档位在本轮只发一次。 */
    void sync(long memberId,Map<String,Object> activity,Map<String,Object> cfg) {
        for(Map<String,Object> pending:pending(memberId,activity,cfg)) {
            data.insert("compute_activity_invite_grant",fields("activity_id",activity.get("id"),"round_no",cfg.getOrDefault("round",1),
                    "member_id",memberId,"member_threshold",pending.get("memberThreshold"),"awarded_count",pending.get("awardedCount"),
                    "qualified_count",pending.get("qualifiedCount"),"minimum_first_deposit",cfg.getOrDefault("minimumFirstDeposit","10"),
                    "qualification_snapshot",data.json(pending.get("sources")),"created_at",now()));
        }
    }

    long total(long memberId,Map<String,Object> activity,Map<String,Object> cfg) {
        Long granted=repository.totalActivityInviteGrantValue(activity.get("id"), cfg.getOrDefault("round",1), memberId);
        long result=granted==null?0:granted;
        for(Map<String,Object> pending:pending(memberId,activity,cfg))result=Math.addExact(result,number(pending.get("awardedCount")));
        return result;
    }

    private List<Map<String,Object>> pending(long memberId,Map<String,Object> activity,Map<String,Object> cfg) {
        if(!"INVITED_MEMBERS".equals(cfg.get("earningType")))return List.of();
        List<Map<String,Object>> granted=repository.pendingActivityInviteGrantRows(activity.get("id"), cfg.getOrDefault("round",1), memberId);
        Set<Long> seen=new HashSet<>();granted.forEach(g->seen.add(number(g.get("memberThreshold"))));
        List<Map<String,Object>> tiers=ComputeWheelService.normalizeTiers(cfg.get("tiers")).stream()
                .filter(t->!seen.contains(decimal(t.get("threshold")).longValueExact())).toList();
        if(tiers.isEmpty())return List.of();
        long needed=tiers.stream().mapToLong(t->decimal(t.get("threshold")).longValueExact()).max().orElseThrow();
        LocalDateTime start=time(activity.get("startsAt"));
        if(number(cfg.getOrDefault("round",1))>1&&time(cfg.get("roundStartedAt")).isAfter(start))start=time(cfg.get("roundStartedAt"));
        var query=new ActivityDepositQuery(memberId,ComputeUsdAccounting.WALLET_CURRENCY,start,time(activity.get("endsAt")),new ComputeTestMemberParticipation(data).since());
        // 首次成功仍查全历史；活动切换点只限制本轮新增奖励的来源事件。
        List<Map<String,Object>> sources=repository.invitedMembers(query,new BigDecimal(text(cfg.getOrDefault("minimumFirstDeposit","10"))),needed);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> tier:tiers) {
            long threshold=decimal(tier.get("threshold")).longValueExact();
            if(sources.size()>=threshold)result.add(fields("memberThreshold",threshold,"awardedCount",tier.get("selfCount"),"qualifiedCount",sources.size(),"sources",sources.subList(0,Math.toIntExact(threshold))));
        }
        return result;
    }

    private static LocalDateTime time(Object value) {
        return OffsetDateTime.parse(text(value)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
