package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeBusinessMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeBusinessService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeBusinessRepository {
    private final ComputeBusinessMapper mapper;

    @Autowired
    public ComputeBusinessRepository(ComputeBusinessMapper mapper) { this.mapper = mapper; }

    public ComputeBusinessRepository(ComputeData data) {
        this(data.mapper(ComputeBusinessMapper.class));
    }

    public List<Map<String, Object>> creditActivityRewardLedgerRows(Object... args) {
        return ComputeData.normalizeRows(mapper.creditActivityRewardLedgerRows(args));
    }

    public Map<String, Object> initializeWalletsMemberRow(Object... args) {
        return RepositoryResults.required(mapper.initializeWalletsMemberRow(args));
    }

    public List<Map<String, Object>> walletsWalletRows(Object... args) {
        return ComputeData.normalizeRows(mapper.walletsWalletRows(args));
    }

    public List<Map<String, Object>> dashboardOrderRows(Object... args) {
        return ComputeData.normalizeRows(mapper.dashboardOrderRows(args));
    }


    public Map<String, Object> transferTransferRow(Object... args) {
        return RepositoryResults.required(mapper.transferTransferRow(args));
    }

    public Map<String, Object> transferQuoteMemberRow(Object... args) {
        return RepositoryResults.required(mapper.transferQuoteMemberRow(args));
    }

    public List<Map<String, Object>> transferOptionsTransferRuleRows(Object... args) {
        return ComputeData.normalizeRows(mapper.transferOptionsTransferRuleRows(args));
    }

    public Map<String, Object> buyOrderProductRow(Object... args) {
        return RepositoryResults.required(mapper.buyOrderProductRow(args));
    }

    public int buyOrderOrderWrite2(Object... args) {
        return mapper.buyOrderOrderWrite2(args);
    }

    public Map<String, Object> buyOrderOrderRow(Object... args) {
        return RepositoryResults.required(mapper.buyOrderOrderRow(args));
    }

    public Map<String, Object> createDepositDepositRow(Object... args) {
        return RepositoryResults.required(mapper.createDepositDepositRow(args));
    }

    public Map<String, Object> quoteMemberRow(Object... args) {
        return RepositoryResults.required(mapper.quoteMemberRow(args));
    }

    public Integer createWithdrawalAddressCount(Object... args) {
        return RepositoryResults.scalar(mapper.createWithdrawalAddressCount(args), Integer.class);
    }

    public Map<String, Object> createWithdrawalWithdrawalRow(Object... args) {
        return RepositoryResults.required(mapper.createWithdrawalWithdrawalRow(args));
    }

    public Map<String, Object> creditMcagDepositDepositRow(Object... args) {
        return RepositoryResults.required(mapper.creditMcagDepositDepositRow(args));
    }

    public Map<String, Object> reviewDepositInternalDepositRow(Object... args) {
        return RepositoryResults.required(mapper.reviewDepositInternalDepositRow(args));
    }

    public Map<String, Object> reviewDepositInternalDepositRow2(Object... args) {
        return RepositoryResults.required(mapper.reviewDepositInternalDepositRow2(args));
    }

    public int reviewDepositInternalDepositWrite(Object... args) {
        return mapper.reviewDepositInternalDepositWrite(args);
    }

    public int reviewDepositInternalDepositWrite2(Object... args) {
        return mapper.reviewDepositInternalDepositWrite2(args);
    }

    public Map<String, Object> reviewWithdrawalWithdrawalRow(Object... args) {
        return RepositoryResults.required(mapper.reviewWithdrawalWithdrawalRow(args));
    }

    public Map<String, Object> reviewWithdrawalMemberRow(Object... args) {
        return RepositoryResults.required(mapper.reviewWithdrawalMemberRow(args));
    }

    public Map<String, Object> reviewWithdrawalWithdrawalRow2(Object... args) {
        return RepositoryResults.required(mapper.reviewWithdrawalWithdrawalRow2(args));
    }

    public List<Map<String, Object>> reviewWithdrawalCurrencyAssetRows(Object... args) {
        return ComputeData.normalizeRows(mapper.reviewWithdrawalCurrencyAssetRows(args));
    }

    public int reviewWithdrawalWithdrawalWrite(Object... args) {
        return mapper.reviewWithdrawalWithdrawalWrite(args);
    }

    public List<Map<String, Object>> directApproveWithdrawalMcagPayoutRows(Object... args) {
        return ComputeData.normalizeRows(mapper.directApproveWithdrawalMcagPayoutRows(args));
    }

    public List<Map<String, Object>> directApproveWithdrawalLedgerRows(Object... args) {
        return ComputeData.normalizeRows(mapper.directApproveWithdrawalLedgerRows(args));
    }

    public int directApproveWithdrawalWithdrawalWrite(Object... args) {
        return mapper.directApproveWithdrawalWithdrawalWrite(args);
    }

    public int queueWithdrawalWithdrawalWrite(Object... args) {
        return mapper.queueWithdrawalWithdrawalWrite(args);
    }

    public int completeMcagWithdrawalWithdrawalWrite(Object... args) {
        return mapper.completeMcagWithdrawalWithdrawalWrite(args);
    }

    public Map<String, Object> settleOrderOrderRow(Object... args) {
        return RepositoryResults.required(mapper.settleOrderOrderRow(args));
    }

    public Map<String, Object> settleOrderOrderRow2(Object... args) {
        return RepositoryResults.required(mapper.settleOrderOrderRow2(args));
    }

    public int settleOrderOrderWrite(Object... args) {
        return mapper.settleOrderOrderWrite(args);
    }





    public List<Map<String, Object>> saveAddressAddressRows(Object... args) {
        return ComputeData.normalizeRows(mapper.saveAddressAddressRows(args));
    }

    public int saveAddressAddressWrite(Object... args) {
        return mapper.saveAddressAddressWrite(args);
    }

    public Map<String, Object> saveAddressAddressRow(Object... args) {
        return RepositoryResults.required(mapper.saveAddressAddressRow(args));
    }

    public Map<String, Object> deleteAddressAddressRow(Object... args) {
        return RepositoryResults.required(mapper.deleteAddressAddressRow(args));
    }

    public int deleteAddressAddressWrite(Object... args) {
        return mapper.deleteAddressAddressWrite(args);
    }

    public List<Map<String, Object>> withdrawalQuoteFeeRuleRows(Object... args) {
        return ComputeData.normalizeRows(mapper.withdrawalQuoteFeeRuleRows(args));
    }

    public Long withdrawalQuoteWithdrawalCount(Object... args) {
        return RepositoryResults.scalar(mapper.withdrawalQuoteWithdrawalCount(args), Long.class);
    }

    public List<Map<String, Object>> withdrawalFundsWalletRows(Object... args) {
        return ComputeData.normalizeRows(mapper.withdrawalFundsWalletRows(args));
    }

    public int finishWithdrawalAccountingWithdrawalWrite(Object... args) {
        return mapper.finishWithdrawalAccountingWithdrawalWrite(args);
    }

    public int ensureWalletWalletWrite(Object... args) {
        return mapper.ensureWalletWalletWrite(args);
    }

    public Map<String, Object> changeWalletWalletRow(Object... args) {
        return RepositoryResults.required(mapper.changeWalletWalletRow(args));
    }

    public int changeWalletWalletWrite(Object... args) {
        return mapper.changeWalletWalletWrite(args);
    }

    public int annotateLedgerLedgerWrite(Object... args) {
        return mapper.annotateLedgerLedgerWrite(args);
    }

    public String requirePayPasswordMemberValue(Object... args) {
        return RepositoryResults.scalar(mapper.requirePayPasswordMemberValue(args), String.class);
    }

    public Map<String, Object> lockMemberMemberRow(Object... args) {
        return RepositoryResults.required(mapper.lockMemberMemberRow(args));
    }

    public List<Map<String, Object>> requestResultRequestRows(Object... args) {
        return ComputeData.normalizeRows(mapper.requestResultRequestRows(args));
    }

    public int finishRequestRequestWrite(Object... args) {
        return mapper.finishRequestRequestWrite(args);
    }

    public Map<String,Object> dashboardTotals(long memberId, String currency) {
        return RepositoryResults.required(mapper.dashboardTotals(memberId, currency));
    }

    public List<Map<String, Object>> currencyTotals(long memberId) {
        return ComputeData.normalizeRows(mapper.currencyTotals(memberId));
    }

    public List<Map<String, Object>> transferRules(String from, String to, String currency, BigDecimal amount, boolean lock) {
        return ComputeData.normalizeRows(mapper.transferRules(from, to, currency, amount, lock));
    }

    public List<Map<String, Object>> transferChannels(String currency, boolean lock) {
        return ComputeData.normalizeRows(mapper.transferChannels(currency, lock));
    }

    public Map<String,Object> withdrawalAddress(long id, long memberId, String currency, String network, boolean lock) {
        return RepositoryResults.required(mapper.withdrawalAddress(id, memberId, currency, network, lock));
    }

    public Integer activeCurrencyCount(String currency, String network) {
        return mapper.activeCurrencyCount(currency, network);
    }

    public Map<String,Object> dailyIncome(long memberId,String currency,LocalDate today,ZoneId zone) { return RepositoryResults.required(mapper.dailyIncome(memberId,currency,today,zone)); }
    public static final class Queries {
        private Queries() { }
        public static String dailyIncome(Map<String,Object> parameters) {
            long memberId=(long)parameters.get("memberId");String summaryCurrency=(String)parameters.get("currency");
            LocalDate today=(LocalDate)parameters.get("today");ZoneId zone=(ZoneId)parameters.get("zone");
        String settlements="(SELECT s.member_id,o.currency,s.created_at,s.income FROM compute_order_settlement s JOIN compute_order o ON o.id=s.order_id UNION ALL SELECT s.member_id,o.currency,s.created_at,s.income FROM compute_auto_settlement s JOIN compute_auto_contract o ON o.id=s.contract_id) settlements";

        // 按实际入账时间归属自然日；一次条件聚合，不依赖数据库时区表，也不改变历史结算日期。
        List<Object> args=new ArrayList<>();List<String> sums=new ArrayList<>();
        for(int index=0;index<7;index++) {
            LocalDate day=today.minusDays(6-index);
            sums.add("COALESCE(SUM(CASE WHEN created_at>=? AND created_at<? THEN income ELSE 0 END),0) day_"+index);
            args.add(LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(),ZoneOffset.UTC));
            args.add(LocalDateTime.ofInstant(day.plusDays(1).atStartOfDay(zone).toInstant(),ZoneOffset.UTC));
        }
        args.add(memberId);args.add(summaryCurrency);
        args.add(LocalDateTime.ofInstant(today.minusDays(6).atStartOfDay(zone).toInstant(),ZoneOffset.UTC));
        args.add(LocalDateTime.ofInstant(today.plusDays(1).atStartOfDay(zone).toInstant(),ZoneOffset.UTC));

            return org.token.repository.support.SqlParameters.bind("SELECT "+String.join(",",sums)+" FROM "+settlements+" WHERE member_id=? AND currency=? AND created_at>=? AND created_at<?",args,parameters);
        }
    }
}
