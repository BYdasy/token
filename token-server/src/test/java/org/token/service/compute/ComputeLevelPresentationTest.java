package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离真实查询：等级权益不能混入币网、个人、阶梯或者另一等级的提款规则。 */
@Tag("regression")
class ComputeLevelPresentationTest {
    @Test void levelsShowTheirOwnHighestPriorityRuleOrGlobalFallbackWithoutChannelDetails() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:fee_display_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        var jdbc = new JdbcTemplate(source);
        // 保留一个连接使测试内存库覆盖多次独立查询，结束后自然销毁。
        try (var connection = source.getConnection()) {
            TokenTestSchema.create(jdbc,"compute_fee_rule");
            TokenTestSchema.withdrawalRules(jdbc);
            var data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
            data.insert("compute_fee_rule", fields("name", "全局后备", "rule_type", "GLOBAL", "priority", 10000, "rate", 10, "updated_at", now()));
            data.insert("compute_fee_rule", fields("name", "旧全局", "rule_type", "GLOBAL", "priority", 1, "rate", 20, "updated_at", now()));
            data.insert("compute_fee_rule", fields("name", "一级独立", "rule_type", "LEVEL", "level", 1, "priority", 2, "rate", 2, "fixed_fee", 1, "open_weekdays", "1,5", "updated_at", now()));
            data.insert("compute_fee_rule", fields("name", "一级后序", "rule_type", "LEVEL", "level", 1, "priority", 2, "rate", 3, "updated_at", now()));
            data.insert("compute_fee_rule", fields("name", "二级独立", "rule_type", "LEVEL", "level", 2, "rate", 4, "updated_at", now()));
            data.insert("compute_fee_rule", fields("name", "个人", "rule_type", "PERSONAL", "member_id", 1, "rate", 1, "updated_at", now()));
            data.insert("compute_fee_rule", fields("name", "币网", "rule_type", "CHANNEL", "currency", "BTC", "network", "BITCOIN", "rate", 0, "updated_at", now()));
            data.insert("compute_fee_rule", fields("name", "金额档", "rule_type", "AMOUNT", "min_amount", 10, "max_amount", 100, "rate", 5, "updated_at", now()));
            data.insert("compute_fee_rule", fields("name", "停用等级", "rule_type", "LEVEL", "level", 3, "enabled", 0, "rate", 9, "updated_at", now()));
            var levels = List.of(fields("id", 1), fields("id", 2), fields("id", 3));
            new ComputeLevelPresentation(data).apply(levels);
            assertEquals(List.of("VIP:2.000000", "VIP:4.000000", "GENERAL:10.000000"),
                    levels.stream().map(level -> fee(level).get("source") + ":" + fee(level).get("rate")).toList());
            assertEquals(List.of(1, 5), fee(levels.get(0)).get("openWeekdays"));
            for (var level : levels) {
                var fee = fee(level);
                assertEquals("WITHDRAWAL_CURRENCY", fee.get("amountUnit"));
                for (String field : List.of("currency", "network", "id", "memberId", "level", "minAmount", "maxAmount", "lowAmountThreshold", "lowAmountFee"))
                    assertFalse(fee.containsKey(field), field);
            }
            jdbc.update("UPDATE compute_fee_rule SET enabled=0");
            new ComputeLevelPresentation(data).apply(levels);
            assertEquals(List.of(), levels.get(0).get("withdrawalFees"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fee(Map<String, Object> level) {
        var fees = (List<Map<String, Object>>) level.get("withdrawalFees");
        assertEquals(1, fees.size());
        return fees.get(0);
    }
}
