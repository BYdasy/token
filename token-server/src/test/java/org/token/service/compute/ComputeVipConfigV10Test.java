package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离验证后台真实配置保存与回显，不运行应用调度或部署数据库迁移。 */
@Tag("regression")
class ComputeVipConfigV10Test {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ComputeCatalogService catalog;

    @BeforeEach
    void prepare() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:vip_config_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run");
        TokenTestSchema.apply(jdbc);
        catalog = new ComputeCatalogService(new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules()), new PasswordUtils());
    }

    @Test
    void detailDelayAcceptsOneMinuteAndTwentyThreeHours() {
        for (int minutes : List.of(1, 1380)) {
            Map<String,Object> saved = transaction.execute(status -> catalog.save("levels", form(minutes, "1")));
            assertEquals(minutes, ((Number) saved.get("vipDetailsDelayMinutes")).intValue());
            assertEquals(minutes, jdbc.queryForObject("SELECT vip_details_delay_minutes FROM compute_level WHERE id=?", Integer.class, saved.get("id")));
        }
    }

    @Test
    void directMemberRequirementsAreSavedAndOmittedFieldsPreserveExistingConfiguration() {
        Map<String,Object> input = form(1, "0");
        input.put("requiredDirectMembers", 3); input.put("directMemberDepositThreshold", "100.12345678");
        Map<String,Object> saved = transaction.execute(status -> catalog.save("levels", input));
        assertEquals(Boolean.TRUE, saved.get("autoUpgradeEnabled"));
        assertEquals(3, ((Number) saved.get("requiredDirectMembers")).intValue());
        assertEquals("100.12345678", saved.get("directMemberDepositThreshold"));
        Map<String,Object> changed = form(1, "0"); changed.put("id", saved.get("id"));
        Map<String,Object> updated = transaction.execute(status -> catalog.save("levels", changed));
        assertEquals(saved.get("requiredDirectMembers"), updated.get("requiredDirectMembers"));
        assertEquals(saved.get("directMemberDepositThreshold"), updated.get("directMemberDepositThreshold"));
        changed.put("requiredDirectMembers", 0); changed.put("directMemberDepositThreshold", "0");
        Map<String,Object> disabled = transaction.execute(status -> catalog.save("levels", changed));
        assertEquals(0, ((Number) disabled.get("requiredDirectMembers")).intValue());
    }

    @Test
    void invalidDirectMemberCountsAndDepositThresholdsCannotBeSaved() {
        for (Object count : List.of(-1, 1_000_001, "1.5")) {
            var input = form(1, "0"); input.put("requiredDirectMembers", count);
            assertThrows(BusinessException.class, () -> transaction.execute(status -> catalog.save("levels", input)));
        }
        for (String amount : List.of("-1", "0.000000001", "1000000000000000000", "invalid")) {
            var input = form(1, "0"); input.put("directMemberDepositThreshold", amount);
            assertThrows(BusinessException.class, () -> transaction.execute(status -> catalog.save("levels", input)));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_level", Integer.class));
    }

    @Test
    void displayRateCanChangeWithoutChangingTheRewardRule() {
        Map<String,Object> input = form(1, "1");
        input.put("vipRewardMode", "RATE"); input.put("autoClickRateMin", "0.25"); input.put("autoClickRateMax", "0.5"); input.put("displayReturnRate", "4.125");
        Map<String,Object> saved = transaction.execute(status -> catalog.save("levels", input));
        assertEquals(0, new BigDecimal(saved.get("displayReturnRate").toString()).compareTo(new BigDecimal("4.125")));
        assertEquals(0, new BigDecimal(saved.get("autoClickRateMax").toString()).compareTo(new BigDecimal("0.5")));
        Map<String,Object> changed = new HashMap<>(saved); changed.put("displayReturnRate", "9.75");
        Map<String,Object> updated = transaction.execute(status -> catalog.save("levels", changed));
        assertEquals(saved.get("autoClickRateMin"), updated.get("autoClickRateMin"));
        assertEquals(saved.get("autoClickRateMax"), updated.get("autoClickRateMax"));
        assertEquals(saved.get("vipRewardMode"), updated.get("vipRewardMode"));
        changed.put("displayReturnRate", "100.01");
        assertThrows(BusinessException.class, () -> transaction.execute(status -> catalog.save("levels", changed)));
    }

    @Test
    void detailDelayRejectsZeroAndValuesBeyondTwentyThreeHoursWithoutWriting() {
        for (int minutes : List.of(0, 1381)) {
            assertThrows(BusinessException.class, () -> transaction.execute(status -> catalog.save("levels", form(minutes, "1"))));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_level", Integer.class));
    }

    @Test
    void sevenDayCooldownIsStoredAsSecondsAndDisplayedAsHours() {
        Map<String,Object> saved = transaction.execute(status -> catalog.save("levels", form(60, "168")));
        assertEquals(604800L, jdbc.queryForObject("SELECT auto_cooldown_seconds FROM compute_level WHERE id=?", Long.class, saved.get("id")));
        assertEquals(0, new BigDecimal(saved.get("autoCooldownHours").toString()).compareTo(new BigDecimal("168")));
    }

    @Test
    void secondsSurviveHoursDisplayAndSaveWithoutRoundingLoss() {
        Map<String,Object> saved = transaction.execute(status -> catalog.save("levels", form(1, "0")));
        Object id = saved.get("id");
        for (int seconds : List.of(0, 1, 59, 60, 3599, 86401, 604801, 31_536_000)) {
            jdbc.update("UPDATE compute_level SET auto_cooldown_seconds=? WHERE id=?", seconds, id);
            Map<String,Object> displayed = catalog.list("levels").get(0);
            assertInstanceOf(Boolean.class, displayed.get("autoUpgradeEnabled"));
            assertNotNull(displayed.get("autoCooldownHours"));
            transaction.execute(status -> catalog.save("levels", new HashMap<>(displayed)));
            assertEquals(seconds, jdbc.queryForObject("SELECT auto_cooldown_seconds FROM compute_level WHERE id=?", Integer.class, id), "秒数在小时回显后必须保持：" + seconds);
        }
    }

    @Test
    void providerBindingIsSavedPreservedAndExplicitlyCleared() {
        jdbc.update("INSERT INTO compute_provider(id,code,name,enabled) VALUES(1,'provider-a','供应商甲',1),(2,'provider-b','供应商乙',0)");
        Map<String,Object> input = form(1, "0"); input.put("autoProviderIds", List.of(1));
        Map<String,Object> saved = transaction.execute(status -> catalog.save("levels", input));
        assertEquals(List.of(1L), saved.get("autoProviderIds"));
        jdbc.update("UPDATE compute_provider SET enabled=0 WHERE id=1");
        Map<String,Object> unchanged = form(1, "0"); unchanged.put("id", saved.get("id"));
        assertEquals(List.of(1L), transaction.execute(status -> catalog.save("levels", unchanged)).get("autoProviderIds"));
        unchanged.put("autoProviderIds", List.of(1));
        assertEquals(List.of(1L), transaction.execute(status -> catalog.save("levels", unchanged)).get("autoProviderIds"));
        unchanged.put("autoProviderIds", List.of(2));
        assertThrows(BusinessException.class, () -> transaction.execute(status -> catalog.save("levels", unchanged)));
        unchanged.put("autoProviderIds", List.of());
        assertEquals(List.of(), transaction.execute(status -> catalog.save("levels", unchanged)).get("autoProviderIds"));
    }

    private Map<String,Object> form(int minutes, String hours) {
        return new HashMap<>(Map.of("name", "验证等级", "threshold", "0", "enabled", true,
                "autoEnabled", true, "vipDetailsDelayMinutes", minutes, "autoCooldownHours", hours));
    }
}
