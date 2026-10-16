package org.token.service.compute;

import org.token.repository.compute.ComputeRewardRepository;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;
import static org.token.service.compute.ComputeRewardPolicy.*;

/** 真实充值/收益事件驱动的奖励引擎；调用方与奖励账本同一事务，查询接口绝不补发奖励。 */
@Service
public class ComputeRewardService {
    public ComputeRewardService(ComputeData data, ComputeMemberRelations relations, ComputeLevelNames levelNames) {
        this(data,new ComputeRewardRepository(data),relations,levelNames);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeRewardService(ComputeData data,ComputeRewardRepository repository,ComputeMemberRelations relations,ComputeLevelNames levelNames) {
        this.data=data;this.repository=repository;this.relations=relations;this.levelNames=levelNames;
    }

    private final ComputeData data;
    private final ComputeRewardRepository repository;
    private final ComputeMemberRelations relations;
    private final ComputeLevelNames levelNames;

    public ComputeRewardService(ComputeData data, ComputeMemberRelations relations) {
        this(data, relations, null);
    }

    /** 必须在资金入口首次锁会员/钱包之前调用，统一邀请迁移与多级入账的锁顺序。 */
    @Transactional
    public void lock() { relations.lock(); }

    @Transactional(readOnly=true)
    public Map<String,Object> settings() {
        Map<String,Object> row=configuration();Map<String,Object> result=config(row);
        result.put("revision",row.get("revision"));result.put("activated",row.get("activatedAt")!=null);
        result.put("teamDepth",teamDepth());
        result.put("currencies",repository.settingsCurrencyAssetRows());
        return result;
    }

    @Transactional
    public Map<String,Object> save(Map<String,Object> request,int operator) {
        lock();Map<String,Object> current=configuration();
        if(number(request.get("revision"))!=number(current.get("revision")))throw new BusinessException("server.compute.reward.configChanged");
        Map<String,Object> normalized=normalizeConfiguration(request);
        for(String key:List.of("depositRules","effectiveRules"))for(Map<String,Object> row:maps(normalized.get(key),32))
            if(repository.saveCurrencyAssetCount(row.get("currency"))!=1)throw new BusinessException("server.compute.unsupportedCurrency");
        Object activated=current.get("activatedAt")==null?java.time.Instant.now().toString():current.get("activatedAt");
        String document=data.json(fields("configuration",normalized,"activatedAt",activated));
        if(repository.saveRewardConfigurationWrite(document,now(),operator,current.get("revision"))!=1)
            throw new BusinessException("server.compute.reward.configChanged");
        return settings();
    }

    /** true 表示新引擎已接管该充值，旧的等额多层充值返奖不得再次执行。 */
    @Transactional
    public boolean onDepositApproved(long depositId) {
        lock();
        Map<String,Object> configuration=configuration();if(configuration.get("activatedAt")==null)return false;
        Map<String,Object> deposit=repository.onDepositApprovedDepositRow(depositId);
        if(!"APPROVED".equals(deposit.get("status")))throw new BusinessException("server.compute.reward.sourceInvalid");
        String event="DEPOSIT:"+depositId;
        if(processed(event))return true;
        // 奖励仅处理启用边界之后的新事件，不追溯发放。
        if(beforeActivation(deposit.get("reviewedAt"),configuration)||
                !new ComputeTestMemberParticipation(data).allows(deposit.get("isTest"),deposit.get("reviewedAt"))) {mark(event);return true;}
        long member=number(deposit.get("memberId"));
        boolean unified=ComputeUsdAccounting.version(deposit)==2;
        if(unified && (!"USD".equals(deposit.get("accountingCurrency")) || !"POSTED".equals(deposit.get("accountingStatus"))
                || decimal(deposit.get("accountingAmount")).signum()<=0 || decimal(deposit.get("accountingRate")).signum()<=0))
            throw new BusinessException("server.compute.reward.sourceInvalid");
        String currency=unified?ComputeUsdAccounting.WALLET_CURRENCY:text(deposit.get("currency"));
        BigDecimal source=decimal(deposit.get(unified?"accountingAmount":"amount"));
        if(repository.onDepositApprovedLedgerCount(member, event, currency, source)!=1)
            throw new BusinessException("server.compute.reward.sourceInvalid");
        Map<String,Object> rules=config(configuration);List<Map<String,Object>> ancestors=ancestors(member);
        ComputeRewardEligibility eligibility=new ComputeRewardEligibility(data);eligibility.lockMembers(member,ancestors);
        for(Map<String,Object> rule:maps(rules.get("depositRules"),32)) {
            if(!enabled(rule.get("enabled"))||!currency.equals(ruleCurrency(rule)))continue;
            if("FIRST".equals(rule.get("participation"))&&firstDeposit(member)!=depositId)continue;
            Map<String,Object> selected=null;
            for(Map<String,Object> tier:maps(rule.get("tiers"),50))if(source.compareTo(decimal(tier.get("threshold")))>=0)selected=tier;
            if(selected==null)continue;
            award(event,"DEPOSIT_SELF",depositId,member,member,0,currency,source,reward(source,selected.get("selfMode"),selected.get("selfValue")),"SELF",selected);
            if(!ancestors.isEmpty())award(event,"DEPOSIT_DIRECT",depositId,member,number(ancestors.get(0).get("id")),1,currency,source,
                    reward(source,selected.get("directMode"),selected.get("directValue")),"DIRECT",selected);
        }
        if(!ancestors.isEmpty())effectiveRewards(event,depositId,member,number(ancestors.get(0).get("id")),currency,rules);
        if(firstDeposit(member)==depositId)
            vipRewards(event,"VIP_FIRST_DEPOSIT",depositId,member,currency,source,"firstDeposit",ancestors,eligibility);
        vipRewards(event,"VIP_DEPOSIT",depositId,member,currency,source,"deposit",ancestors,eligibility);
        mark(event);return true;
    }

    /** 来源必须是已落库的本金之外收益；账本唯一事件键保证多次调度只发一次。 */
    @Transactional
    public void onIncomePosted(long memberId,String eventKey) {
        lock();
        List<Map<String,Object>> rows=repository.onIncomePostedLedgerRows(memberId, eventKey);
        if(rows.size()!=1)throw new BusinessException("server.compute.reward.sourceInvalid");
        Map<String,Object> source=rows.get(0);String kind=text(source.get("kind"));
        if(!Set.of("AUTO_INCOME","ORDER_INCOME").contains(kind))throw new BusinessException("server.compute.reward.sourceInvalid");
        long id=number(source.get("id"));String event="INCOME:"+id;if(processed(event))return;
        // VIP 分层规则独立于充值奖励配置启用；空规则不会生成奖励。
        if(!new ComputeTestMemberParticipation(data).allows(source.get("isTest"),source.get("createdAt"))||decimal(source.get("amount")).signum()<=0){mark(event);return;}
        List<Map<String,Object>> ancestors=ancestors(memberId);
        ComputeRewardEligibility eligibility=new ComputeRewardEligibility(data);eligibility.lockMembers(memberId,ancestors);
        vipRewards(event,"AUTO_INCOME".equals(kind)?"VIP_INCOME":"PRODUCT_INCOME",id,memberId,text(source.get("currency")),decimal(source.get("amount")),
                "AUTO_INCOME".equals(kind)?"vip":"product",ancestors,eligibility);
        mark(event);
    }

    private void effectiveRewards(String event,long depositId,long sourceMember,long owner,String currency,Map<String,Object> configuration) {
        for(Map<String,Object> rule:maps(configuration.get("effectiveRules"),32)) {
            if(!enabled(rule.get("enabled"))||!currency.equals(ruleCurrency(rule)))continue;
            long qualified=qualifiedCount(owner,rule,null);if(qualifiedCount(owner,rule,sourceMember)==0)continue;
            for(Map<String,Object> tier:maps(rule.get("tiers"),50)) {
                long count=number(tier.get("count"));if(qualified<count)continue;
                String milestone="EFFECTIVE:"+currency+":"+count;
                if(repository.effectiveRewardsRewardAwardCount(owner, milestone)>0)continue;
                award(event,"EFFECTIVE",depositId,sourceMember,owner,1,currency,BigDecimal.valueOf(qualified),decimal(tier.get("amount")),milestone,
                        fields("basis",rule.get("basis"),"threshold",rule.get("threshold"),"count",count,"amount",tier.get("amount")));
            }
        }
    }

    private long qualifiedCount(long owner,Map<String,Object> rule,Long customer) {
        return repository.qualifiedCount(owner,ruleCurrency(rule),customer,"FIRST".equals(rule.get("basis")),decimal(rule.get("threshold")));
    }

    private void vipRewards(String event,String sourceKind,long sourceId,long sourceMember,String currency,BigDecimal amount,String kind,List<Map<String,Object>> ancestors,ComputeRewardEligibility eligibility) {
        int maximum=teamDepth();
        for(int index=0;index<Math.min(maximum,ancestors.size());index++) {
            Map<String,Object> ancestor=ancestors.get(index);long recipient=number(ancestor.get("id"));int depth=index+1;
            List<Map<String,Object>> levels=repository.vipRewardsMemberRows(recipient, now());
            if(levels.isEmpty())continue;
            for(Map<String,Object> rule:readVipRules(data,levels.get(0).get("rewardRulesJson"))) {
                if(number(rule.get("depth"))!=depth||!enabled(rule.get(kind+"Enabled")))continue;
                var decision=eligibility.evaluate(sourceMember,recipient,currency,amount,kind,rule);
                if(decision.eligible())
                    award(event,sourceKind,sourceId,sourceMember,recipient,depth,currency,amount,
                            reward(decision.baseAmount(),"PERCENT",rule.get(kind+"Rate")),"VIP:"+kind+":"+depth,decision.snapshot());
            }
        }
    }

    private void award(String event,String kind,long sourceId,long sourceMember,long recipient,int depth,String currency,BigDecimal sourceAmount,BigDecimal amount,String rule,Map<String,Object> snapshot) {
        if(amount.signum()<=0)return;
        Map<String,Object> member=repository.awardMemberRow(recipient);if(!enabled(member.get("enabled")))return;
        if(repository.awardRewardAwardCount(event, recipient, rule)>0)return;
        boolean test=enabled(member.get("isTest"));long id=data.insert("compute_reward_award",fields("event_key",event,
                "member_id",recipient,"source_member_id",sourceMember,"source_kind",kind,"source_id",sourceId,"rule_key",rule,"depth",depth,
                "currency",currency,"amount",amount,"source_amount",sourceAmount,"is_virtual",test,"rule_snapshot",data.json(snapshot),"created_at",now()));
        repository.awardWalletWrite(recipient, currency, now());
        Map<String,Object> wallet=repository.awardWalletRow(recipient, currency);
        BigDecimal before=decimal(wallet.get("available")),after=before.add(amount),frozen=decimal(wallet.get("frozen"));
        repository.awardWalletWrite2(after, now(), wallet.get("id"));
        data.insert("compute_ledger",fields("member_id",recipient,"wallet_type","EARNINGS","currency",currency,"kind",test?"TEAM_REWARD_TEST":"TEAM_REWARD",
                "amount",amount,"frozen_change",BigDecimal.ZERO,"available_before",before,"available_after",after,"frozen_before",frozen,"frozen_after",frozen,
                "reference_id",String.valueOf(id),"event_key","V9_REWARD:"+id,"created_at",now()));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> center(long memberId) {
        Map<String,Object> member=repository.centerMemberRow(memberId),configuration=configuration(),rules=config(configuration);
        List<Map<String,Object>> deposits=new ArrayList<>(),effective=new ArrayList<>();
        if(configuration.get("activatedAt")!=null) {
            for(Map<String,Object> rule:maps(rules.get("depositRules"),32))if(enabled(rule.get("enabled"))) {
                Map<String,Object> item=new LinkedHashMap<>(rule);item.put("firstDepositCompleted",firstDeposit(memberId)>0);item.put("currency",ruleCurrency(rule));
                item.put("received",total(memberId,"DEPOSIT_SELF",ruleCurrency(rule)).add(total(memberId,"DEPOSIT_DIRECT",ruleCurrency(rule))).toPlainString());deposits.add(item);
            }
            for(Map<String,Object> rule:maps(rules.get("effectiveRules"),32))if(enabled(rule.get("enabled"))) {
                Map<String,Object> item=new LinkedHashMap<>(rule);long count=qualifiedCount(memberId,rule,null);item.put("effectiveCount",count);item.put("currency",ruleCurrency(rule));
                item.put("received",total(memberId,"EFFECTIVE",ruleCurrency(rule)).toPlainString());
                List<Map<String,Object>> tiers=new ArrayList<>();Map<String,Object> next=null;
                for(Map<String,Object> tier:maps(rule.get("tiers"),50)) {
                    Map<String,Object> status=new LinkedHashMap<>(tier);String key="EFFECTIVE:"+ruleCurrency(rule)+":"+tier.get("count");
                    boolean received=repository.effectiveRewardsRewardAwardCount(memberId, key)>0;
                    status.put("received",received);
                    tiers.add(status);if(next==null&&!received)next=status;
                }
                item.put("tiers",tiers);item.put("nextTier",next);effective.add(item);
            }
        }
        List<Map<String,Object>> levels=repository.centerLevelRows(memberId, now());
        List<Map<String,Object>> vip=new ArrayList<>();
        if(!levels.isEmpty())for(Map<String,Object> rule:readVipRules(data,levels.get(0).get("rewardRulesJson")))
            if(number(rule.get("depth"))<=teamDepth()&&(enabled(rule.get("firstDepositEnabled"))||enabled(rule.get("depositEnabled"))||enabled(rule.get("vipEnabled"))||enabled(rule.get("productEnabled"))))vip.add(rule);
        return fields("depositRules",deposits,"effectiveRules",effective,"vipRules",vip,"level",member.get("level"),"levelName",levelNames == null ? "" : levelNames.name(member.get("level")),"teamDepth",teamDepth(),"isTest",enabled(member.get("isTest")),
                "totals",repository.centerRewardAwardRows(memberId, memberId),
                "records",records(memberId,1,10));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> records(long memberId,int page,int size) {
        int limit=Math.min(100,Math.max(1,size));long offset=(long)(Math.max(1,page)-1)*limit;
        return fields("records",repository.recordsPage(memberId,limit,offset),
                "total",repository.recordsRewardAwardCount(memberId, memberId));
    }
    private BigDecimal total(long member,String kind,String currency) {return decimal(repository.totalRewardAwardValue(member, kind, currency));}
    private static String ruleCurrency(Map<String,Object> rule) {
        String value=text(rule.get("currency"));
        return Set.of("USD","USDT","USDC","USDT0").contains(value)?ComputeUsdAccounting.WALLET_CURRENCY:value;
    }
    private List<Map<String,Object>> ancestors(long member) {
        List<Map<String,Object>> result=new ArrayList<>(relations.ancestors(member));Collections.reverse(result);result.remove(0);
        // 完整关系仍检查环及最大深度，但只锁、处理支持范围内的上级；直属全局奖励也在该范围内。
        return new ArrayList<>(result.subList(0,Math.min(teamDepth(),result.size())));
    }
    private long firstDeposit(long member) {List<Map<String,Object>> rows=repository.firstDepositDepositRows(member);return rows.isEmpty()?0:number(rows.get(0).get("id"));}
    private boolean processed(String event){return repository.processedRewardEventCount(event)>0;}
    private void mark(String event){repository.markRewardEventWrite(event, now());}
    private boolean beforeActivation(Object timestamp,Map<String,Object> cfg){return timestamp==null||java.time.Instant.parse(text(timestamp)).isBefore(java.time.Instant.parse(text(cfg.get("activatedAt"))));}
    private Map<String,Object> configuration(){return repository.configurationRewardConfigurationRow();}
    private Map<String,Object> config(Map<String,Object> row){return data.jsonMap(row.get("configurationJson"));}
    private int teamDepth(){return (int)Math.min(200,Math.max(1,number(data.setting("teamSupportedDepth","3"))));}
}
