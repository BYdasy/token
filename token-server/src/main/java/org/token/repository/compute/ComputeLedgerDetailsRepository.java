package org.token.repository.compute;

import java.util.*;
import static org.token.repository.compute.ComputeData.*;
import org.token.exception.BusinessException;
import org.token.model.query.MemberScope;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeLedgerDetailsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeLedgerDetails 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeLedgerDetailsRepository {
    private final ComputeLedgerDetailsMapper mapper;

    @Autowired
    public ComputeLedgerDetailsRepository(ComputeLedgerDetailsMapper mapper) { this.mapper = mapper; }

    public ComputeLedgerDetailsRepository(ComputeData data) {
        this(data.mapper(ComputeLedgerDetailsMapper.class));
    }

    public List<Map<String, Object>> rewardFactsDepositRows(Object... args) {
        return ComputeData.normalizeRows(mapper.rewardFactsDepositRows(args));
    }
    // 资金审计保留已入账的本金、冻结和释放类型；这些类型不代表当前可发起的业务入口。
    public static final Set<String> DETAILS = Set.of("TOKEN_INCOME", "PROJECT_INCOME", "DOWNLINE_TOKEN_REBATE",
            "DOWNLINE_PROJECT_REBATE", "DOWNLINE_FIRST_DEPOSIT_REBATE", "DOWNLINE_DEPOSIT_REBATE",
            "DIRECT_DEPOSIT_TIER_REBATE", "SELF_DEPOSIT_TIER_REWARD", "EFFECTIVE_INVITE_REWARD",
            "LEGACY_REFERRAL_REWARD", "CHEST_REWARD", "WHEEL_REWARD", "RED_PACKET_REWARD",
            "LEGACY_ACTIVITY_REWARD", "CHECKIN_REWARD", "REGISTRATION_REWARD", "DEPOSIT", "TRANSFER_OUT",
            "TRANSFER_IN", "ORDER_BUY", "ORDER_PRINCIPAL", "AUTO_START", "AUTO_PRINCIPAL", "AUTO_RELEASE",
            "WITHDRAW_FREEZE", "WITHDRAW_APPROVED", "WITHDRAW_RELEASE", "ADMIN_CREDIT", "ADMIN_DEBIT",
            "SALARY_CREDIT", "SALARY_DEBIT", "SOURCE_CONFLICT");

    /** 来源主键、事件协议、接收人、金额、币种全部匹配，才给出具体分类。 */
    public static String table() {
        String rewardMatch="r.member_id=l.member_id AND "+same("r.currency","l.currency")+" AND r.amount=l.amount AND l.wallet_type='EARNINGS' AND l.frozen_change=0";
        String activityMatch="p.member_id=l.member_id AND ("+same("p.currency","l.currency")+" OR (p.currency IN ('USD','USDT','USDC') AND l.currency='USDT')) AND p.amount=l.amount AND p.status='PAID' AND l.wallet_type='EARNINGS' AND l.frozen_change=0";
        return "(SELECT l.*,r.id detail_reward_id,p.id detail_activity_id,CASE "
                + "WHEN l.kind IN ('TEAM_REWARD','TEAM_REWARD_TEST') AND r.id IS NOT NULL AND NOT ("+rewardMatch+") THEN 'SOURCE_CONFLICT' "
                + "WHEN l.kind IN ('TEAM_REWARD','TEAM_REWARD_TEST') AND r.id IS NOT NULL THEN CASE r.source_kind "
                + "WHEN 'VIP_INCOME' THEN 'DOWNLINE_TOKEN_REBATE' WHEN 'PRODUCT_INCOME' THEN 'DOWNLINE_PROJECT_REBATE' "
                + "WHEN 'VIP_FIRST_DEPOSIT' THEN 'DOWNLINE_FIRST_DEPOSIT_REBATE' WHEN 'VIP_DEPOSIT' THEN 'DOWNLINE_DEPOSIT_REBATE' "
                + "WHEN 'DEPOSIT_DIRECT' THEN 'DIRECT_DEPOSIT_TIER_REBATE' WHEN 'DEPOSIT_SELF' THEN 'SELF_DEPOSIT_TIER_REWARD' "
                + "WHEN 'EFFECTIVE' THEN 'EFFECTIVE_INVITE_REWARD' ELSE 'LEGACY_REFERRAL_REWARD' END "
                + "WHEN l.kind IN ('TEAM_REWARD','TEAM_REWARD_TEST') THEN 'LEGACY_REFERRAL_REWARD' "
                + "WHEN l.kind='ACTIVITY_REWARD' AND p.id IS NOT NULL AND NOT ("+activityMatch+") THEN 'SOURCE_CONFLICT' "
                + "WHEN l.kind='ACTIVITY_REWARD' AND p.id IS NOT NULL THEN CASE p.activity_type "
                + "WHEN 'CHEST' THEN 'CHEST_REWARD' WHEN 'WHEEL' THEN 'WHEEL_REWARD' WHEN 'RED_PACKET' THEN 'RED_PACKET_REWARD' ELSE 'LEGACY_ACTIVITY_REWARD' END "
                + "WHEN l.kind='ACTIVITY_REWARD' THEN 'LEGACY_ACTIVITY_REWARD' "
                + "WHEN l.kind='AUTO_INCOME' THEN 'TOKEN_INCOME' WHEN l.kind='ORDER_INCOME' THEN 'PROJECT_INCOME' "
                + "WHEN l.kind='ADMIN_SYSTEM' THEN CASE WHEN l.amount>=0 THEN 'ADMIN_CREDIT' ELSE 'ADMIN_DEBIT' END "
                + "WHEN l.kind='ADMIN_SALARY' THEN CASE WHEN l.amount>=0 THEN 'SALARY_CREDIT' ELSE 'SALARY_DEBIT' END "
                + "ELSE l.kind END detail_type FROM compute_ledger l "
                + "LEFT JOIN compute_reward_award r ON l.kind IN ('TEAM_REWARD','TEAM_REWARD_TEST') AND r.id="+numericReference()+" AND "+same("l.event_key","CONCAT('V9_REWARD:',r.id)")+" "
                + "LEFT JOIN compute_activity_participation p ON l.kind='ACTIVITY_REWARD' AND p.id="+numericReference()+" AND "+same("l.event_key","CONCAT('ACTIVITY:',p.id)")+")";
    }

    // 跨表编码按字节比对，不依赖历史表/连接各自的排序规则。
    private static String same(String left,String right) { return "CAST("+left+" AS BINARY(255))=CAST("+right+" AS BINARY(255))"; }

    private static String numericReference() {
        return "CASE WHEN l.reference_id REGEXP '^[0-9]{1,19}$' THEN CAST(l.reference_id AS DECIMAL(20,0)) ELSE NULL END";
    }

    public static String filters(Map<String,String> query,String prefix,List<Object> args) {
        String detail=text(query.get("detailType"));
        String filter="";
        if(!detail.isEmpty()) {
            if(!DETAILS.contains(detail)) throw new BusinessException("server.compute.management.invalidFilter");
            args.add(detail);filter=" AND "+prefix+"detail_type=?";
        }
        String direction=text(query.get("direction"));
        if(!direction.isEmpty()) filter+=" AND "+switch(direction) {
            case "IN" -> prefix+"amount>0 AND "+prefix+"kind NOT IN ('WITHDRAW_RELEASE','AUTO_RELEASE')";
            case "OUT" -> "(("+prefix+"amount<0 AND "+prefix+"kind<>'WITHDRAW_FREEZE') OR ("+prefix+"kind='WITHDRAW_APPROVED' AND "+prefix+"frozen_change<0))";
            case "FROZEN" -> prefix+"frozen_change<>0 AND "+prefix+"kind<>'WITHDRAW_APPROVED'";
            default -> throw new BusinessException("server.compute.management.invalidFilter");
        };
        return filter;
    }


    public List<Map<String,Object>> sources(String table, Collection<Long> ids) { return ComputeData.normalizeRows(mapper.sources(table, ids)); }
    public List<Map<String,Object>> visibleNames(Collection<Long> ids, MemberScope scope) { return ComputeData.normalizeRows(mapper.visibleNames(ids, scope)); }
    public List<Map<String,Object>> incomeSources(Collection<Long> ids) { return ComputeData.normalizeRows(mapper.incomeSources(ids)); }
    public List<Map<String,Object>> paymentSources(Collection<Long> ids) { return ids.isEmpty()?List.of():ComputeData.normalizeRows(mapper.paymentSources(ids)); }
    public static final class Queries {
        private Queries() { }
        @SuppressWarnings("unchecked")
        public static String paymentSources(Map<String,Object> parameters) {
            var ids=(Collection<Long>)parameters.get("ids");
            List<Object> args=new ArrayList<>();List<String> branches=new ArrayList<>();
            for (String type : List.of("DEPOSIT", "WITHDRAWAL")) {
                boolean deposit="DEPOSIT".equals(type);
                String table=deposit?"compute_deposit":"compute_withdrawal";
                String identity=deposit?"f.id="+numericReference():"(f.id="+numericReference()+" OR "+same("f.order_no","l.reference_id")+")";
                String wallet=deposit?"f.wallet_type":"'EARNINGS'";
                branches.add("SELECT l.id ledger_id,f.id business_id,f.order_no business_order_no,f.member_id source_member_id,"+wallet+" source_wallet_type,f.currency source_currency,f.amount source_amount,f.status source_status,f.accounting_version,f.accounting_currency,f.accounting_amount,s.usd_amount snapshot_amount,'"+type+"' business_type "
                        +"FROM compute_ledger l JOIN "+table+" f ON "+identity
                        +" LEFT JOIN compute_finance_snapshot s ON s.business_type='"+type+"' AND s.business_id=f.id AND s.member_id=f.member_id"
                        +" WHERE l.kind "+(deposit?"='DEPOSIT'":"IN ('WITHDRAW_FREEZE','WITHDRAW_APPROVED','WITHDRAW_RELEASE')")
                        +" AND l.id IN ("+MemberScopeQuery.placeholders(ids.size())+")");
                args.addAll(ids);
            }
            return org.token.repository.support.SqlParameters.bind(String.join(" UNION ALL ",branches),args,parameters);
        }
        @SuppressWarnings("unchecked")
        public static String sources(Map<String,Object> parameters) {
            String table=(String)parameters.get("table");
            if (!Set.of("compute_reward_award", "compute_activity_participation").contains(table)) throw new IllegalArgumentException("不支持的流水来源");
            var ids=(Collection<Long>)parameters.get("ids");
            return org.token.repository.support.SqlParameters.bind("SELECT * FROM "+table+" WHERE id IN ("+MemberScopeQuery.placeholders(ids.size())+")", new ArrayList<>(ids), parameters);
        }
        @SuppressWarnings("unchecked")
        public static String visibleNames(Map<String,Object> parameters) {
            var ids=(Collection<Long>)parameters.get("ids");
            List<Object> args=new ArrayList<>(ids);
            String where="id IN ("+MemberScopeQuery.placeholders(ids.size())+")"+MemberScopeQuery.memberFilter("id",(MemberScope)parameters.get("scope"),args);
            return org.token.repository.support.SqlParameters.bind("SELECT id,username FROM compute_member WHERE "+where,args,parameters);
        }
        @SuppressWarnings("unchecked")
        public static String incomeSources(Map<String,Object> parameters) {
            var ids=(Collection<Long>)parameters.get("ids");
            String sql="SELECT l.id,l.order_no,l.member_id,l.kind,l.amount,l.currency,o.order_no project_order_no,o.product_name,o.product_type "
                + "FROM compute_ledger l LEFT JOIN compute_order o ON l.kind='ORDER_INCOME' AND o.id="+numericReference()
                + " AND o.member_id=l.member_id AND "+same("o.currency","l.currency")+" WHERE l.id IN ("+MemberScopeQuery.placeholders(ids.size())+")";
            return org.token.repository.support.SqlParameters.bind(sql,new ArrayList<>(ids),parameters);
        }
    }
}
