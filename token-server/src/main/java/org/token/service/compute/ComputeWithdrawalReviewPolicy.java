package org.token.service.compute;

import org.token.repository.compute.ComputeWithdrawalReviewPolicyRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeTokenAmounts;
import java.math.BigDecimal;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 本地提款审核策略；只读当前配置和已保存汇率，不缓存资金决策或外呼。 */
public final class ComputeWithdrawalReviewPolicy {
    public static final String SETTING_KEY = "withdrawalReview";
    private final ComputeData data;
    private final ComputeWithdrawalReviewPolicyRepository repository;
    public ComputeWithdrawalReviewPolicy(ComputeData data) { this.data = data;
        this.repository = new ComputeWithdrawalReviewPolicyRepository(data);
    }

    public Map<String,Object> configuration() {
        String stored = data.setting(SETTING_KEY, "");
        return stored == null || stored.isBlank() ? fields("mode","ALL","thresholdToken","0") : normalize(data.jsonMap(stored));
    }

    public Map<String,Object> save(Map<String,Object> request) {
        var config = normalize(request);
        repository.saveSettingWrite(SETTING_KEY, data.json(config), now());
        return config;
    }

    private static Map<String,Object> normalize(Map<String,Object> input) {
        if (!input.keySet().equals(Set.of("mode","thresholdToken")) || !Set.of("ALL","THRESHOLD").contains(text(input.get("mode"))))
            throw new BusinessException("server.compute.invalidInput");
        BigDecimal threshold;
        try { threshold = new BigDecimal(text(input.get("thresholdToken"))); }
        catch (NumberFormatException ex) { throw new BusinessException("server.compute.invalidAmount"); }
        if (threshold.signum() < 0 || threshold.scale() > 8 || threshold.compareTo(new BigDecimal("1000000000000")) > 0
                || ("THRESHOLD".equals(input.get("mode")) && threshold.signum() == 0)) throw new BusinessException("server.compute.invalidAmount");
        return fields("mode", input.get("mode"), "thresholdToken",threshold.toPlainString());
    }

    /** 以手续费前的申请金额比较（达到阈值即审核），决定和汇率写入订单快照。 */
    public Map<String,Object> decision(String currency, BigDecimal amount, boolean gatewayPayment, boolean testMember) {
        var result = new LinkedHashMap<>(configuration());
        result.put("required",true);
        result.put("reason","ALL");
        if (testMember || !gatewayPayment) { result.put("reason",testMember?"TEST":"MANUAL_CHANNEL"); return result; }
        if (!"THRESHOLD".equals(result.get("mode"))) return result;
        BigDecimal rate;
        try { rate = ComputeTokenAmounts.rate(data,currency); }
        catch (BusinessException unavailable) {
            if (!"server.compute.v10.rateUnavailable".equals(unavailable.getMessage())) throw unavailable;
            result.put("reason","RATE_UNAVAILABLE");
            return result;
        }
        BigDecimal token = ComputeTokenAmounts.asToken(amount,rate);
        boolean required = token.compareTo(decimal(result.get("thresholdToken"))) >= 0;
        result.putAll(fields("required",required,"reason",required?"THRESHOLD":"BELOW_THRESHOLD","currency",currency,
                "amount",amount.toPlainString(),"rate",rate.toPlainString(),"amountToken",token.toPlainString(),"evaluatedAt",now().toString()));
        return result;
    }

    /** 新版提款按本次实际冻结的美元金额审核，不再读取可能变化的展示汇率。 */
    public Map<String,Object> decision(String currency, BigDecimal amount, boolean gatewayPayment, boolean testMember,
                                       BigDecimal accountingRate, BigDecimal accountingAmount) {
        if (accountingRate == null || accountingRate.signum() <= 0 || accountingAmount == null
                || accountingAmount.signum() <= 0 || accountingAmount.compareTo(ComputeUsdAccounting.withdrawalUsd(amount, accountingRate)) != 0)
            throw new BusinessException("server.compute.requestConflict");
        var result = new LinkedHashMap<>(configuration());
        result.putAll(fields("required",true,"reason","ALL","currency",currency,"amount",amount.toPlainString(),
                "rate",accountingRate.toPlainString(),"amountToken",accountingAmount.toPlainString(),
                "accountingCurrency","USD","accountingAmount",accountingAmount.toPlainString(),"evaluatedAt",now().toString()));
        if (testMember || !gatewayPayment) { result.put("reason",testMember?"TEST":"MANUAL_CHANNEL"); return result; }
        if (!"THRESHOLD".equals(result.get("mode"))) return result;
        boolean required = accountingAmount.compareTo(decimal(result.get("thresholdToken"))) >= 0;
        result.put("required",required);
        result.put("reason",required?"THRESHOLD":"BELOW_THRESHOLD");
        return result;
    }
}
