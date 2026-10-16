package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.config.EnabledLocaleResolver;
import org.token.model.response.I18nLanguageOption;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 仅用隔离内存数据库验证展示估值、未读统计和配置守卫，不连接真实资产或翻译服务。 */
class ComputePresentationServiceTest {
    JdbcTemplate jdbc; FixtureComputeData data; ComputePresentationService service;
    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:display_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(source); // 每个测试保持独立内存库。
        source.setURL(source.getURL() + ";DB_CLOSE_DELAY=-1");
        data = new FixtureComputeData(jdbc, new ObjectMapper());
        var locales = mock(EnabledLocaleResolver.class);
        when(locales.normalize(anyString())).thenCallRealMethod();
        when(locales.resolve(anyString())).thenAnswer(input -> input.getArgument(0));
        when(locales.enabledLanguages()).thenReturn(List.of(new I18nLanguageOption("English", "en"), new I18nLanguageOption("中文", "zh")));
        service = new ComputePresentationService(data, locales);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_currency(currency VARCHAR(16))");
        jdbc.update("INSERT INTO compute_currency VALUES('USDT'),('BTC')");
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,username VARCHAR(50),level INT,security_score INT,enabled INT,inviter_id BIGINT)");
        jdbc.update("INSERT INTO compute_member VALUES(1,'member',1,90,1,NULL),(2,'child',1,60,1,1)");
        jdbc.execute("CREATE TABLE compute_wallet(member_id BIGINT,wallet_type VARCHAR(20),currency VARCHAR(16),available DECIMAL(28,8),frozen DECIMAL(28,8))");
        jdbc.execute("CREATE TABLE compute_level(id INT,name VARCHAR(40),threshold DECIMAL(28,8),enabled INT)");
        jdbc.update("INSERT INTO compute_level VALUES(1,'VIP1',0,1),(2,'VIP2',100,1)");
        jdbc.execute("CREATE TABLE compute_order(member_id BIGINT,amount DECIMAL(28,8),currency VARCHAR(16),product_type VARCHAR(30),status VARCHAR(20),order_no VARCHAR(50),product_name VARCHAR(40),product_id BIGINT,starts_at TIMESTAMP,matures_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_auto_contract(member_id BIGINT,amount DECIMAL(28,8),currency VARCHAR(16),status VARCHAR(20),paid_income DECIMAL(28,8))");
        TokenTestSchema.create(jdbc, "compute_ledger", "compute_reward_award");
        jdbc.execute("CREATE TABLE compute_deposit(member_id BIGINT,amount DECIMAL(28,8),currency VARCHAR(16),wallet_type VARCHAR(16),status VARCHAR(20))");
        jdbc.execute("CREATE TABLE compute_content(id BIGINT,kind VARCHAR(20),enabled INT,deleted INT,status VARCHAR(20),starts_at TIMESTAMP,ends_at TIMESTAMP,task_mode INT,task_type VARCHAR(20))");
        jdbc.execute("CREATE TABLE compute_content_recipient(content_id BIGINT,member_id BIGINT,read_at TIMESTAMP)");
        jdbc.execute("ALTER TABLE compute_member ADD COLUMN is_test INT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE compute_level ADD COLUMN required_direct_members INT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE compute_level ADD COLUMN direct_member_deposit_threshold DECIMAL(28,8) NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE compute_deposit ADD COLUMN id BIGINT AUTO_INCREMENT PRIMARY KEY");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_version INT DEFAULT 1");
        jdbc.execute("ALTER TABLE compute_deposit ADD accounting_amount DECIMAL(28,8)");
        jdbc.execute("CREATE TABLE compute_finance_snapshot(business_type VARCHAR(16),business_id BIGINT,member_id BIGINT,usd_amount DECIMAL(38,8),PRIMARY KEY(business_type,business_id))");
    }
    @Test void configurableUnitNameIsPublicPresentationOnlyAndSurvivesOlderForms() {
        assertEquals("token", service.publicSettings().get("unitName"));
        var config = ComputePresentationService.defaults();
        config.put("unitName", "  Vora Points  ");
        service.save(config);
        assertEquals("Vora Points", service.publicSettings().get("unitName"));
        assertEquals("token", ((Map<?,?>)service.publicSettings().get("valuation")).get("unit"));
        assertEquals("1", ((Map<?,?>)service.publicSettings().get("valuation")).get("usdPerToken"));
        config.remove("unitName");
        service.save(config);
        assertEquals("Vora Points", service.settings().get("unitName"));
        config.put("unitName", " "); service.save(config);
        assertEquals("token", service.publicSettings().get("unitName"));
    }
    @Test void invalidDisplayUnitDoesNotWriteConfiguration() {
        for (Object name : List.of("a".repeat(25), "USD\nToken", "<b>USD</b>", "US\u202eD", 123)) {
            var config = ComputePresentationService.defaults(); config.put("unitName", name);
            assertThrows(BusinessException.class, () -> service.save(config));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_setting", Integer.class));
        }
    }
    @Test void unpricedBalancesAndFrozenFundsNeverBecomeACompleteTotal() {
        var config = ComputePresentationService.defaults();
        config.put("valuation", Map.of("assetUsdRates", Map.of("USDT", "1"), "fiatPerUsd", Map.of("USD", "1")));
        service.save(config);
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'EARNINGS','USDT',25,100),(1,'COMPUTE','USDT',50,20),(1,'PROJECT','BTC',2,0),(2,'EARNINGS','USDT',999,0)");
        Map<?, ?> assets = (Map<?, ?>) service.member(1).get("assets");
        assertEquals(false, assets.get("complete")); assertEquals("75.00000000", assets.get("totalToken"));
        assertEquals("25.00000000", assets.get("availableToken")); assertEquals("50.00000000", assets.get("participatingToken"));
        assertEquals(Set.of("BTC"), assets.get("unpricedCurrencies"));
        assertEquals(0, jdbc.queryForObject("SELECT frozen FROM compute_wallet WHERE member_id=1 AND currency='USDT' AND wallet_type='EARNINGS'", java.math.BigDecimal.class).compareTo(new java.math.BigDecimal("100")));
    }
    @Test void configuredRatesAreDisplayOnlyAndNeverChangeWallets() {
        var config = ComputePresentationService.defaults(); config.put("valuation", Map.of("assetUsdRates", Map.of("USDT", "1", "BTC", "100"), "fiatPerUsd", Map.of("USD", "1", "EUR", "0.9")));
        service.save(config); jdbc.update("INSERT INTO compute_wallet VALUES(1,'PROJECT','BTC',2,9)");
        var assets = (Map<?, ?>) service.member(1).get("assets"); assertEquals(true, assets.get("complete")); assertEquals("200.00000000", assets.get("totalToken"));
        assertEquals("2.00000000", jdbc.queryForObject("SELECT available FROM compute_wallet", java.math.BigDecimal.class).toPlainString());
    }
    @Test void vipProgressMatchesCurrentComputeBalanceAndNeverUsesHistoricalDepositOrDisplayValuation() {
        var config = ComputePresentationService.defaults();
        config.put("valuation", Map.of("assetUsdRates", Map.of("USDT", "1", "BTC", "1000"), "fiatPerUsd", Map.of("USD", "1")));
        config.put("displayGift", Map.of("enabled", true, "amountToken", "9999"));
        service.save(config);
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'COMPUTE','USDT',25,1000),(1,'COMPUTE','BTC',1000,0),(1,'PROJECT','USDT',9999,0)");
        jdbc.update("INSERT INTO compute_deposit(member_id,amount,currency,wallet_type,status) VALUES(1,9999,'USDT','COMPUTE','APPROVED')");
        var progress = (Map<?, ?>) service.member(1).get("vipProgress");
        assertEquals("25.00000000", progress.get("availableBalance"));
        assertEquals(progress.get("availableBalance"), progress.get("invested"));
        assertEquals("75.00000000", progress.get("remaining"));
        assertEquals("25.00", progress.get("percent"));
        assertEquals("USDT", progress.get("currency"));
    }
    @Test void displayGiftAddsAnExactSeparateTotalForEveryMemberWithoutChangingBusinessState() {
        var config = ComputePresentationService.defaults();
        service.save(config);
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'EARNINGS','USDT',25,100),(1,'COMPUTE','USDT',50,20),(2,'PROJECT','USDT',999,0)");
        jdbc.update("INSERT INTO compute_deposit(member_id,amount,currency,wallet_type,status) VALUES(1,40,'USDT','COMPUTE','APPROVED')");
        award(1, 2, "VIP_DEPOSIT", "USDT", "3", false);
        var before = service.member(1);
        var wallets = data.rows("SELECT * FROM compute_wallet ORDER BY member_id,wallet_type");
        var deposits = data.rows("SELECT * FROM compute_deposit ORDER BY id");
        config.put("displayGift", Map.of("enabled", true, "amountToken", "123.12345678"));
        service.save(config);
        var after = service.member(1);
        assertEquals(Map.of("totalToken", "198.12345678", "giftToken", "123.12345678"), after.get("displayAssets"));
        assertEquals("1122.12345678", ((Map<?,?>)service.member(2).get("displayAssets")).get("totalToken"));
        before.remove("displayAssets"); after.remove("displayAssets");
        assertEquals(before, after, "原资产、VIP、收益及推荐数据均不受展示赠送影响");
        assertEquals(wallets, data.rows("SELECT * FROM compute_wallet ORDER BY member_id,wallet_type"));
        assertEquals(deposits, data.rows("SELECT * FROM compute_deposit ORDER BY id"));
        for (String table : List.of("compute_ledger", "compute_order", "compute_auto_contract"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award", Integer.class));
    }
    @Test void changingOrDisablingGiftChangesOnlyTheDisplayAndNeverExposesGuestGiftConfiguration() {
        var config = ComputePresentationService.defaults();
        var initial = (Map<?,?>)service.member(1).get("displayAssets");
        assertEquals("0", initial.get("giftToken"));
        config.put("displayGift", Map.of("enabled", true, "amountToken", "100"));
        service.save(config);
        assertEquals("100", ((Map<?,?>)service.member(1).get("displayAssets")).get("totalToken"));
        config.put("displayGift", Map.of("enabled", true, "amountToken", "200"));
        service.save(config);
        assertEquals("200", ((Map<?,?>)service.member(1).get("displayAssets")).get("totalToken"));
        config.remove("displayGift");
        service.save(config);
        assertEquals("200", ((Map<?,?>)service.member(1).get("displayAssets")).get("giftToken"), "旧版表单未提交赠送配置时保留原值");
        config.put("displayGift", Map.of("enabled", false, "amountToken", "200"));
        assertEquals("200", ((Map<?,?>)service.save(config).get("displayGift")).get("amountToken"));
        assertEquals(Map.of("totalToken", "0", "giftToken", "0"), service.member(1).get("displayAssets"));
        assertFalse(service.publicSettings().containsKey("displayGift"));
        assertFalse(service.runtime().containsKey("displayGift"));
        assertThrows(BusinessException.class, () -> service.member(0));
        jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=1");
        assertThrows(BusinessException.class, () -> service.member(1));
    }
    @Test void giftCannotHideUnpricedWalletsOrBeAddedToAvailableAndParticipatingBalances() {
        var config = ComputePresentationService.defaults();
        config.put("displayGift", Map.of("enabled", true, "amountToken", "500"));
        service.save(config);
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'EARNINGS','USDT',25,100),(1,'COMPUTE','USDT',50,20),(1,'PROJECT','BTC',2,0)");
        var result = service.member(1);
        var assets = (Map<?,?>)result.get("assets");
        assertEquals(false, assets.get("complete"));
        assertEquals(Set.of("BTC"), assets.get("unpricedCurrencies"));
        assertEquals("75.00000000", assets.get("totalToken"));
        assertEquals("25.00000000", assets.get("availableToken"));
        assertEquals("50.00000000", assets.get("participatingToken"));
        assertEquals("575.00000000", ((Map<?,?>)result.get("displayAssets")).get("totalToken"));
    }
    @Test void displayGiftRejectsInvalidAmountBeforeSavingAnyConfiguration() {
        var config = ComputePresentationService.defaults();
        for (Object value : List.of("-1", "0.000000001", "1000000000000.00000001", "NaN", "invalid")) {
            config.put("displayGift", Map.of("enabled", true, "amountToken", value));
            assertThrows(BusinessException.class, () -> service.save(config));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_setting", Integer.class));
        }
        config.put("displayGift", Map.of("enabled", true, "amountToken", "1000000000000"));
        assertEquals("1000000000000", ((Map<?,?>)service.save(config).get("displayGift")).get("amountToken"));
    }
    @Test void unreadCountsExcludeRecalledExpiredReadAndOtherMembers() {
        jdbc.update("INSERT INTO compute_content(id,kind,enabled,deleted,status,starts_at,ends_at) VALUES(1,'MESSAGE',1,0,'PUBLISHED',NULL,NULL),(2,'TASK',1,0,'RECALLED',NULL,NULL),(3,'MESSAGE',1,0,'PUBLISHED',NULL,TIMESTAMP '2000-01-01 00:00:00'),(4,'TASK',1,0,'PUBLISHED',NULL,NULL)");
        jdbc.update("INSERT INTO compute_content_recipient VALUES(1,1,NULL),(2,1,NULL),(3,1,NULL),(4,2,NULL)");
        assertEquals(1L, service.unread(1).get("total")); assertEquals(1L, service.unread(1).get("messages"));
    }
    @Test void onlyPersonalBranchesContributeToMessagesAndTasksHaveNoUnreadTab() {
        for(int mode=1;mode<=6;mode++) {
            jdbc.update("INSERT INTO compute_content(id,kind,enabled,deleted,status,task_mode) VALUES(?,'TASK',1,0,'PUBLISHED',?)",mode,mode);
            jdbc.update("INSERT INTO compute_content_recipient VALUES(?,1,NULL)",mode);
        }
        assertEquals(3L,service.unread(1).get("messages"));assertEquals(0L,((Number)service.unread(1).get("tasks")).longValue());assertEquals(3L,service.unread(1).get("total"));
        jdbc.update("UPDATE compute_content_recipient SET read_at=CURRENT_TIMESTAMP WHERE content_id=4");
        assertEquals(2L,service.unread(1).get("total"));
    }
    @Test void inconsistentRuntimeAndInvalidPricesAreRejectedBeforeSave() {
        var config = ComputePresentationService.defaults();
        config.put("runtime", Map.of("mode", "MANUAL", "totalNodes", 10, "activeNodes", 20, "totalPools", 2, "runningPools", 1, "capacity", "10", "consumed", "11"));
        assertThrows(BusinessException.class, () -> service.save(config));
        config.put("runtime", ComputePresentationService.defaults().get("runtime"));
        config.put("valuation", Map.of("assetUsdRates", Map.of("UNKNOWN", "2"), "fiatPerUsd", Map.of("USD", "2")));
        assertThrows(BusinessException.class, () -> service.save(config));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_setting", Integer.class));
    }
    @Test void automaticRuntimeRecalculatesHiddenValuesWhenCapacityIsReduced() {
        var config = ComputePresentationService.defaults();
        var runtime = ComputeRuntimeProjection.defaults();
        runtime.put("totalNodes", 2); runtime.put("totalPools", 1); runtime.put("capacity", "0.5");
        config.put("runtime", runtime);
        var saved = (Map<?,?>) service.save(config).get("runtime");
        assertTrue(((Number)saved.get("activeNodes")).longValue() <= 2);
        assertTrue(new java.math.BigDecimal(saved.get("consumed").toString()).compareTo(new java.math.BigDecimal("0.5")) <= 0);
    }
    @Test void publicRuntimeSamplesAreNeverPersistedAsConfiguration() {
        var config = ComputePresentationService.defaults();
        var runtime = ComputeRuntimeProjection.defaults();
        runtime.put("enabled", true);
        config.put("runtime", ComputeRuntimeProjection.project(runtime, java.time.Instant.parse("2026-10-17T12:00:00Z")));
        var saved = (Map<?,?>) service.save(config).get("runtime");
        var persisted = (Map<?,?>) data.jsonMap(data.setting(ComputePresentationService.SETTING_KEY, "{}")).get("runtime");
        for (String field : List.of("samples", "sampleSeconds", "serverTime", "updatedAt", "nextUpdateAt", "simulated", "utilizationPercent")) {
            assertFalse(saved.containsKey(field));
            assertFalse(persisted.containsKey(field));
        }
        var publicRuntime = service.runtime();
        assertFalse(((List<?>) publicRuntime.get("samples")).isEmpty());
        assertEquals(60L, ((Number) persisted.get("refreshSeconds")).longValue());
    }
    @Test void unsafeSlidesAndDisabledDefaultLanguagesAreRejected() {
        var config = ComputePresentationService.defaults(); config.put("slides", List.of(Map.of("imageUrl", "javascript:alert(1)", "targetPath", "/home")));
        assertThrows(BusinessException.class, () -> service.save(config));
        config.put("slides", List.of(Map.of("imageUrl", "/banner.webp", "targetPath", "//outside.invalid")));
        assertThrows(BusinessException.class, () -> service.save(config));
        config.put("slides", List.of()); config.put("defaultLanguage", "fr"); assertThrows(BusinessException.class, () -> service.save(config));
    }
    @Test void vipProgressUsesComputingBalanceIndependentOfProductOrdersAndDeposits() {
        jdbc.update("INSERT INTO compute_order(member_id,amount,currency) VALUES(1,9999,'USDT'),(1,1000,'BTC'),(2,900,'USDT')");
        jdbc.update("INSERT INTO compute_deposit(member_id,amount,currency,wallet_type,status) VALUES(1,40,'USDT','COMPUTE','APPROVED'),(1,1000,'BTC','COMPUTE','APPROVED'),(2,900,'USDT','COMPUTE','APPROVED'),(1,888,'USDT','PROJECT','APPROVED'),(1,777,'USDT','COMPUTE','PENDING')");
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'COMPUTE','USDT',30,10),(2,'COMPUTE','USDT',900,0)");
        var result = service.member(1); var progress = (Map<?, ?>) result.get("vipProgress");
        assertEquals("30.00000000", progress.get("availableBalance")); assertEquals("70.00000000", progress.get("remaining")); assertEquals("30.00", progress.get("percent"));
        assertEquals(1L, ((Map<?, ?>)result.get("invitation")).get("memberCount"));
    }

    @Test void vipProgressIncludesPerLevelDirectRequirementsAndHighestLevelHasNoNextRequirement() {
        jdbc.update("UPDATE compute_level SET required_direct_members=2,direct_member_deposit_threshold=100 WHERE id=2");
        jdbc.update("INSERT INTO compute_deposit(member_id,amount,currency,wallet_type,status) VALUES(1,100,'USDT','COMPUTE','APPROVED'),(2,0.001,'BTC','PROJECT','APPROVED')");
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'COMPUTE','USDT',100,0)");
        jdbc.update("INSERT INTO compute_finance_snapshot SELECT 'DEPOSIT',id,member_id,100 FROM compute_deposit WHERE member_id=2");
        var progress = (Map<?, ?>) service.member(1).get("vipProgress");
        assertEquals(2L, progress.get("requiredDirectMembers"));
        assertEquals(1L, progress.get("qualifiedDirectMembers"));
        assertEquals(1L, progress.get("remainingDirectMembers"));
        assertEquals(false, progress.get("directMemberRequirementMet"));
        assertEquals("50.00", progress.get("percent"));
        assertEquals(2, ((List<?>) progress.get("levels")).size());
        jdbc.update("UPDATE compute_member SET level=2 WHERE id=1");
        var highest = (Map<?, ?>) service.member(1).get("vipProgress");
        assertNull(highest.get("nextLevel"));
        assertEquals(true, highest.get("directMemberRequirementMet"));
        assertEquals(0L, highest.get("requiredDirectMembers"));
        assertEquals("100", highest.get("percent"));
    }

    @Test void tokenIncomeUsesTheConfiguredBusinessDayAndExcludesOtherIncomeKinds() {
        var settings = mock(org.token.service.SystemSettingService.class);
        when(settings.resolveSystemTimeZone()).thenReturn("America/New_York");
        var zone = java.time.ZoneId.of("America/New_York");
        var day = org.token.repository.compute.ComputeData.now().toInstant(java.time.ZoneOffset.UTC).atZone(zone).toLocalDate();
        var start = java.time.LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(), java.time.ZoneOffset.UTC);
        var end = java.time.LocalDateTime.ofInstant(day.plusDays(1).atStartOfDay(zone).toInstant(), java.time.ZoneOffset.UTC);
        ledger(1, "USDT", "AUTO_INCOME", "2", start.minusSeconds(1), "before");
        ledger(1, "USDT", "AUTO_INCOME", "3", start, "current");
        ledger(1, "USDT", "AUTO_INCOME", "7", end, "after");
        ledger(1, "USDT", "ORDER_INCOME", "50", start, "order");
        ledger(2, "USDT", "AUTO_INCOME", "100", start, "other");
        ledger(1, "BTC", "AUTO_INCOME", "99", start, "btc");
        var locales = mock(EnabledLocaleResolver.class); when(locales.resolve(anyString())).thenReturn("en");
        var service = new ComputePresentationService(data, locales, null, settings);
        assertEquals("3.00000000", ((Map<?,?>) service.member(1).get("autoSummary")).get("todayIncome"));
    }

    @Test void invitationUsesConfiguredDescendantsAndCurrentRelationshipsWithoutChangingFunds() {
        jdbc.update("INSERT INTO compute_member(id,username,level,security_score,enabled,inviter_id) VALUES(3,'second',1,90,0,2),(4,'third',1,90,1,3),(5,'fourth',1,90,1,4),(900,'outside',1,90,1,NULL)");
        jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=2");
        award(1, 2, "VIP_FIRST_DEPOSIT", "USDT", "5", true);
        award(2, 3, "VIP_INCOME", "USDC", "7", true);
        award(3, 4, "PRODUCT_INCOME", "USDT0", "3", true);
        award(4, 5, "VIP_DEPOSIT", "USDT", "100", true);
        award(5, 900, "VIP_DEPOSIT", "USDT", "1000", true);
        var originalAwards = data.rows("SELECT * FROM compute_reward_award ORDER BY id");
        var originalLedger = data.rows("SELECT * FROM compute_ledger ORDER BY id");
        var invitation = (Map<?,?>) service.member(1).get("invitation");
        assertEquals(3L, invitation.get("memberCount"));
        assertEquals("15.00000000", invitation.get("totalRewardsToken"));
        assertEquals(true, invitation.get("complete"));
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','teamSupportedDepth','2')");
        invitation = (Map<?,?>) service.member(1).get("invitation");
        assertEquals(2L, invitation.get("memberCount"));
        assertEquals("12.00000000", invitation.get("totalRewardsToken"));
        jdbc.update("UPDATE compute_member SET inviter_id=900 WHERE id=3");
        invitation = (Map<?,?>) service.member(1).get("invitation");
        assertEquals(1L, invitation.get("memberCount"));
        assertEquals("5.00000000", invitation.get("totalRewardsToken"));
        assertEquals(originalAwards, data.rows("SELECT * FROM compute_reward_award ORDER BY id"));
        assertEquals(originalLedger, data.rows("SELECT * FROM compute_ledger ORDER BY id"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_wallet", Integer.class));
    }

    @Test void invitationRequiresExactPostedRebatesAndNeverPricesThemWithCurrentMarketRates() {
        var config = ComputePresentationService.defaults();
        config.put("valuation", Map.of("assetUsdRates", Map.of("BTC", "100000"), "fiatPerUsd", Map.of("USD", "1")));
        service.save(config);
        award(1, 2, "VIP_INCOME", "USDT", "2", true);
        award(2, 2, "VIP_INCOME", "BTC", "0.5", true);
        award(3, 2, "VIP_DEPOSIT", "USDT", "7", false);
        award(4, 1, "DEPOSIT_SELF", "USDT", "3000", true);
        award(5, 2, "EFFECTIVE", "USDT", "4000", true);
        award(6, 2, "DEPOSIT_DIRECT", "USDT", "8", true);
        jdbc.update("UPDATE compute_ledger SET amount=9 WHERE reference_id='6'");
        var invitation = (Map<?,?>) service.member(1).get("invitation");
        assertEquals("2.00000000", invitation.get("totalRewardsToken"));
        assertEquals(false, invitation.get("complete"));
        @SuppressWarnings("unchecked") var rewards = (List<Map<String,Object>>) invitation.get("rewardsByCurrency");
        assertEquals(Set.of(Map.of("currency", "USDT", "amount", "2.00000000"), Map.of("currency", "BTC", "amount", "0.50000000")), new HashSet<>(rewards));
    }

    private void award(long id, long source, String kind, String currency, String amount, boolean posted) {
        jdbc.update("INSERT INTO compute_reward_award(id,order_no,event_key,member_id,source_member_id,source_kind,source_id,rule_key,depth,currency,amount,source_amount,rule_snapshot,created_at) VALUES(?,?,?,1,?,?,?, ?,1,?,?,?,'{}',CURRENT_TIMESTAMP)",
                id, "RW"+id, "SOURCE:"+id, source, kind, id, "RULE:"+id, currency, new java.math.BigDecimal(amount), new java.math.BigDecimal(amount));
        if (posted) ledger(1, currency, "TEAM_REWARD", amount, ComputeData.now(), String.valueOf(id));
    }

    private void ledger(long member, String currency, String kind, String amount, java.time.LocalDateTime at, String reference) {
        jdbc.update("INSERT INTO compute_ledger(member_id,wallet_type,currency,kind,amount,available_after,frozen_after,reference_id,event_key,created_at) VALUES(?,'EARNINGS',?,?,?,0,0,?,?,?)",
                member, currency, kind, new java.math.BigDecimal(amount), reference, "TEAM_REWARD".equals(kind) ? "V9_REWARD:"+reference : "TEST:"+reference, at);
    }
}
