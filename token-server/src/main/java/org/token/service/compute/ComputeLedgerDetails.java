package org.token.service.compute;

import org.token.repository.compute.ComputeLedgerDetailsRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import java.math.BigDecimal;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 流水只读明细：复用入账来源，不改金额、幂等键或历史资金记录。 */
final class ComputeLedgerDetails {
    private ComputeLedgerDetails() { }

    static void enrich(ComputeData data,List<Map<String,Object>> rows,ComputeAgentScope scope,Long viewer) {
        if(rows.isEmpty()) return;
        Map<Long,Map<String,Object>> rewards=sources(data,rows,"detailRewardId","compute_reward_award");
        Map<Long,Map<String,Object>> activities=sources(data,rows,"detailActivityId","compute_activity_participation");
        Set<Long> sourceMembers=new LinkedHashSet<>();
        rewards.values().forEach(row -> sourceMembers.add(number(row.get("sourceMemberId"))));
        Map<Long,String> names=visibleNames(data,sourceMembers,scope,viewer);
        Set<Long> incomeIds=new LinkedHashSet<>();
        rows.stream().filter(row -> Set.of("AUTO_INCOME","ORDER_INCOME").contains(text(row.get("kind"))))
                .forEach(row -> incomeIds.add(number(row.get("id"))));
        rewards.values().stream().filter(row -> Set.of("VIP_INCOME","PRODUCT_INCOME").contains(text(row.get("sourceKind")))&&names.containsKey(number(row.get("sourceMemberId"))))
                .forEach(row -> incomeIds.add(number(row.get("sourceId"))));
        Map<Long,Map<String,Object>> incomes=incomeSources(data,incomeIds);
        Map<Long,List<Map<String,Object>>> payments=new HashMap<>();
        var paymentIds=rows.stream().filter(row -> Set.of("DEPOSIT","WITHDRAW_FREEZE","WITHDRAW_APPROVED","WITHDRAW_RELEASE").contains(text(row.get("kind"))))
                .map(row -> number(row.get("id"))).toList();
        new ComputeLedgerDetailsRepository(data).paymentSources(paymentIds).forEach(row ->
                payments.computeIfAbsent(number(row.get("ledgerId")),ignored -> new ArrayList<>()).add(row));
        for(var row:rows) {
            String detail=text(row.get("detailType"));Map<String,Object> facts=new LinkedHashMap<>();
            if(!"SOURCE_CONFLICT".equals(detail)) {
                var reward=rewards.get(optionalId(row.get("detailRewardId")));
                if(reward!=null) rewardFacts(data,facts,reward,names,incomes);
                var ownIncome=incomes.get(number(row.get("id")));
                if(ownIncome!=null&&Set.of("AUTO_INCOME","ORDER_INCOME").contains(text(row.get("kind")))) incomeFacts(facts,ownIncome);
                var activity=activities.get(optionalId(row.get("detailActivityId")));
                if(activity!=null) {
                    copy(facts,activity,"orderNo","sourceOrderNo");copy(facts,activity,"activityName","activityName");
                    copy(facts,activity,"prizeName","prizeName");copy(facts,activity,"wheelRound","roundNo");
                    copy(facts,activity,"redemptionType","creditMode");facts.put("postedAt",row.get("createdAt"));
                }
            }
            row.put("detailFacts",facts);
            row.put("detailStatus","SOURCE_CONFLICT".equals(detail)?"CONFLICT":detail.startsWith("LEGACY_")?"UNAVAILABLE":"MATCHED");
            BigDecimal available=decimal(row.get("amount")),frozen=decimal(row.get("frozenChange"));
            row.put("businessAmount",("WITHDRAW_APPROVED".equals(detail)?frozen:available).toPlainString());
            paymentIdentity(row,payments.getOrDefault(number(row.get("id")),List.of()));
            row.remove("detailRewardId");row.remove("detailActivityId");
        }
    }

    /** 单号仅从精确事件及账务事实关联；独立流水号与账本变化始终保留。 */
    private static void paymentIdentity(Map<String,Object> row,List<Map<String,Object>> candidates) {
        row.put("ledgerNo",row.get("orderNo"));row.put("businessOrderNo",null);
        row.put("businessOrderStatus","UNAVAILABLE");
        BigDecimal amount=decimal(row.get("businessAmount"));
        boolean stable=Set.of("USD","USDT","USDC","USDT0").contains(text(row.get("currency")));
        row.put("accountingCurrency","USD");
        row.put("accountingBusinessAmount",stable?amount.toPlainString():null);
        if(candidates.isEmpty())return;
        if(candidates.size()!=1) {row.put("businessOrderStatus","CONFLICT");return;}
        var source=candidates.get(0);long id=number(source.get("businessId"));
        boolean deposit="DEPOSIT".equals(source.get("businessType"));
        String kind=text(row.get("kind")),event=text(row.get("eventKey")),reference=text(row.get("referenceId"));
        boolean protocol=deposit ? event.equals("DEPOSIT:"+id)&&reference.equals(Long.toString(id))
                : "WITHDRAW_FREEZE".equals(kind) ? event.equals("WITHDRAW:"+id+":FREEZE")&&reference.equals(Long.toString(id))
                : (event.equals("WITHDRAW:"+id+":REVIEW")||"WITHDRAW_APPROVED".equals(kind)&&event.equals("WITHDRAW:"+id+":DIRECT"))&&reference.equals(Long.toString(id))
                  ||event.equals("WITHDRAW:"+id+":MCAG_FINAL")&&reference.equals(text(source.get("businessOrderNo")));
        if(!protocol)return;
        boolean usd=source.get("accountingVersion")!=null&&number(source.get("accountingVersion"))==2;
        Object posted=usd?source.get("accountingAmount"):source.get("sourceAmount");
        String walletCurrency=usd?"USDT":text(source.get("sourceCurrency"));
        BigDecimal gross=decimal(posted),available=decimal(row.get("amount")),frozen=decimal(row.get("frozenChange"));
        boolean movement=posted!=null&&gross.signum()>0&&(deposit?available.compareTo(gross)==0&&frozen.signum()==0
                :"WITHDRAW_FREEZE".equals(kind)?available.compareTo(gross.negate())==0&&frozen.compareTo(gross)==0
                :"WITHDRAW_APPROVED".equals(kind)?available.signum()==0&&frozen.compareTo(gross.negate())==0
                :available.compareTo(gross)==0&&frozen.compareTo(gross.negate())==0);
        boolean state=deposit||"WITHDRAW_APPROVED".equals(kind)?"APPROVED".equals(source.get("sourceStatus"))
                :!"WITHDRAW_RELEASE".equals(kind)||"REJECTED".equals(source.get("sourceStatus"));
        if(!movement||!state||number(row.get("memberId"))!=number(source.get("sourceMemberId"))
                ||!text(row.get("walletType")).equals(text(source.get("sourceWalletType")))||!walletCurrency.equals(text(row.get("currency")))
                ||usd&&!"USD".equals(source.get("accountingCurrency"))) {row.put("businessOrderStatus","CONFLICT");return;}
        row.put("businessOrderNo",source.get("businessOrderNo"));row.put("businessOrderStatus","MATCHED");
        Object historical=usd?source.get("accountingAmount"):source.get("snapshotAmount");
        if(historical!=null)row.put("accountingBusinessAmount",decimal(historical).multiply(BigDecimal.valueOf(amount.signum())).toPlainString());
    }

    private static Map<Long,Map<String,Object>> sources(ComputeData data,List<Map<String,Object>> rows,String field,String table) {
        List<Long> ids=rows.stream().filter(row -> !"SOURCE_CONFLICT".equals(row.get("detailType")))
                .map(row -> optionalId(row.get(field))).filter(id -> id>0).distinct().toList();
        Map<Long,Map<String,Object>> result=new HashMap<>();
        if(!ids.isEmpty()) new ComputeLedgerDetailsRepository(data).sources(table, ids)
                .forEach(row -> result.put(number(row.get("id")),row));
        return result;
    }

    private static Map<Long,String> visibleNames(ComputeData data,Set<Long> ids,ComputeAgentScope scope,Long viewer) {
        if(ids.isEmpty()) return Map.of();
        var visible = scope != null ? scope.queryScope() : new org.token.model.query.MemberScope(viewer == null, viewer == null ? List.of() : List.of(viewer));
        Map<Long,String> names=new HashMap<>();
        new ComputeLedgerDetailsRepository(data).visibleNames(ids, visible)
                .forEach(row -> names.put(number(row.get("id")),text(row.get("username"))));
        return names;
    }

    private static Map<Long,Map<String,Object>> incomeSources(ComputeData data,Set<Long> ids) {
        if(ids.isEmpty()) return Map.of();
        Map<Long,Map<String,Object>> result=new HashMap<>();
        new ComputeLedgerDetailsRepository(data).incomeSources(ids)
                .forEach(row -> result.put(number(row.get("id")),row));
        return result;
    }

    private static void incomeFacts(Map<String,Object> facts,Map<String,Object> source) {
        copy(facts,source,"orderNo","sourceOrderNo");copy(facts,source,"projectOrderNo","projectOrderNo");
        copy(facts,source,"productName","productName");copy(facts,source,"productType","productType");
    }

    private static void rewardFacts(ComputeData data,Map<String,Object> facts,Map<String,Object> reward,Map<Long,String> names,Map<Long,Map<String,Object>> incomes) {
        long source=number(reward.get("sourceMemberId"));
        if(names.containsKey(source)) facts.put("sourceUsername",names.get(source));
        copy(facts,reward,"orderNo","rewardOrderNo");copy(facts,reward,"depth","sourceDepth");
        String kind=text(reward.get("sourceKind"));
        Map<String,Object> rule=reward.get("ruleSnapshot")==null?Map.of():data.jsonMap(reward.get("ruleSnapshot"));
        if("EFFECTIVE".equals(kind)) {
            copy(facts,reward,"sourceAmount","effectiveMembers");copy(facts,rule,"count","requiredMembers");
            copy(facts,rule,"threshold","qualificationAmount");facts.put("rewardMode","FIXED");
        } else {
            copy(facts,reward,"sourceAmount","sourceAmount");copy(facts,rule,"rewardBase","rewardBase");
            if(kind.startsWith("DEPOSIT_")) {
                String prefix="DEPOSIT_SELF".equals(kind)?"self":"direct";
                copy(facts,rule,prefix+"Mode","rewardMode");copy(facts,rule,prefix+"Value","rewardValue");
                facts.put("rewardBase",reward.get("sourceAmount"));
            } else {
                String rate=switch(kind){case "VIP_FIRST_DEPOSIT"->"firstDepositRate";case "VIP_DEPOSIT"->"depositRate";case "VIP_INCOME"->"vipRate";case "PRODUCT_INCOME"->"productRate";default->"";};
                if(!rate.isEmpty()&&rule.get(rate)!=null) {facts.put("rewardMode","PERCENT");facts.put("rewardValue",rule.get(rate));}
            }
        }
        if(names.containsKey(source)) {
            if(Set.of("VIP_INCOME","PRODUCT_INCOME").contains(kind)) {
                var income=incomes.get(number(reward.get("sourceId")));
                if(income!=null&&number(income.get("memberId"))==source&&Objects.equals(income.get("currency"),reward.get("currency"))
                        &&decimal(income.get("amount")).compareTo(decimal(reward.get("sourceAmount")))==0
                        &&Objects.equals(income.get("kind"),"VIP_INCOME".equals(kind)?"AUTO_INCOME":"ORDER_INCOME")) incomeFacts(facts,income);
            } else {
                var sources=new ComputeLedgerDetailsRepository(data).rewardFactsDepositRows(reward.get("sourceId"), source);
                if(sources.size()==1) {
                    var deposit=sources.get(0);copy(facts,deposit,"orderNo","sourceOrderNo");
                    copy(facts,deposit,"currency","originalCurrency");copy(facts,deposit,"amount","originalAmount");
                    copy(facts,deposit,"accountingRate","accountingRate");copy(facts,deposit,"accountingAmount","accountingAmount");
                }
            }
        }
    }

    private static long optionalId(Object value) { return value==null?0:number(value); }
    private static void copy(Map<String,Object> to,Map<String,Object> from,String key,String name) {
        if(from.get(key)!=null&&!text(from.get(key)).isEmpty()) to.put(name,from.get(key));
    }
}
