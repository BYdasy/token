package org.token.service.compute;

import org.token.repository.compute.ComputeLevelPresentationRepository;

import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeMoneyPolicy;

import java.util.List;
import java.util.Map;

import static org.token.repository.compute.ComputeData.*;

/** 等级权益仅展示本等级独立提款规则或全局后备，不把个人、币网与金额例外当成等级权益。 */
final class ComputeLevelPresentation {
    private final ComputeData data;
    private final ComputeLevelPresentationRepository repository;

    ComputeLevelPresentation(ComputeData data) { this.data = data;
        this.repository = new ComputeLevelPresentationRepository(data);
    }

    void apply(List<Map<String, Object>> levels) {
        if (levels.isEmpty()) return;
        var rules = repository.applyFeeRuleRows();
        for (var level : levels) {
            Map<String, Object> selected = rules.stream()
                    .filter(rule -> "GLOBAL".equals(rule.get("ruleType")) || number(rule.get("level")) == number(level.get("id")))
                    .findFirst().orElse(null);
            if (selected == null) { level.put("withdrawalFees", List.of()); continue; }
            var fee = fields("source", "LEVEL".equals(selected.get("ruleType")) ? "VIP" : "GENERAL",
                    "amountUnit", "WITHDRAWAL_CURRENCY");
            for (String field : List.of("rate", "fixedFee", "minimumFee", "freeCount", "period",
                    "openTime", "closeTime", "freeStartTime", "freeEndTime")) fee.put(field, selected.get(field));
            fee.put("openWeekdays", ComputeMoneyPolicy.openWeekdays(selected.get("openWeekdays")));
            level.put("withdrawalFees", List.of(fee));
        }
    }
}
