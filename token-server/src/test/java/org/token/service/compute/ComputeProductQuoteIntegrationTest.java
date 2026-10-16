package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/** GPU报价、累计限额、金额精度及并发购买的隔离验证；不连接实际MySQL。 */
@Tag("regression")
class ComputeProductQuoteIntegrationTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private FixtureComputeData data;
    private ComputeBusinessService business;
    private ComputeProductQuoteService quotes;

    @BeforeEach
    void prepare() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:product_quote_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeWorkflowIntegrationTest.utcTimestamp'");
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run");





        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1)");
        TokenTestSchema.apply(jdbc);
        data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        business = new ComputeBusinessService(data);
        quotes = new ComputeProductQuoteService(data);
        jdbc.update("INSERT INTO compute_member(id,username,password_hash,invite_code,created_at,updated_at) VALUES(1,'subscriber','not-used','SUBSCRIBER',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        tx.executeWithoutResult(s -> business.initializeWallets(1));
        jdbc.update("UPDATE compute_wallet SET available=1000 WHERE member_id=1 AND wallet_type='PROJECT'");
        product(1, 1, "100", 10, "C");
        product(2, 2, "200", 20, "A");
        product(3, 3, "300", 30, "D");
    }

    private void product(long id, int rank, String price, int days, String mode) {
        data.insert("compute_product", fields("id", id, "name", "GPU项目" + rank, "type", "GPU", "currency", "USDT",
                "min_amount", new BigDecimal(price), "max_amount", new BigDecimal(price),
                "duration_days", days,
                "daily_rate_min", BigDecimal.ONE, "daily_rate_max", BigDecimal.ONE, "settlement_mode", mode,
                "enabled", 1, "description", "GPU算力", "updated_at", now()));
    }

    private Map<String, Object> purchase(long product, Map<String, Object> quote, String request) {
        return tx.execute(s -> business.buyOrder(1, fields("productId", product, "quoteId", quote.get("quoteId"), "requestId", request)));
    }
    private BigDecimal balance(String wallet) { return jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1 AND wallet_type=? AND currency='USDT'", BigDecimal.class, wallet); }
    private void equalMoney(String value, Object actual) {
        assertEquals(0, new BigDecimal(value).compareTo(decimal(actual)),
                () -> "金额不一致：预期 " + value + "，实际 " + actual);
    }

    @Test
    void mysqlJsonObjectKeyReorderingDoesNotInvalidateGpuQuotes() {
        gpu(1, "AMOUNT", "0", "0", 0);
        gpu(2, "AMOUNT", "0", "0", 0);
        var first = gpuQuote(1, "100", null);
        reorderStoredQuote(first);
        purchase(1, first, "json-order-first-001");
        var upgrade = gpuQuote(2, "200", null);
        reorderStoredQuote(upgrade);
        purchase(2, upgrade, "json-order-upgrade-001");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM compute_order WHERE status='ACTIVE'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_order WHERE status='UPGRADED'", Integer.class));
    }

    private void reorderStoredQuote(Map<String, Object> quote) {
        Map<String, Object> row = data.one("SELECT snapshot_json FROM compute_product_quote WHERE quote_key=?", quote.get("quoteId"));
        jdbc.update("UPDATE compute_product_quote SET snapshot_json=? WHERE quote_key=?",
                data.json(sortedJson(data.jsonMap(row.get("snapshotJson")))), quote.get("quoteId"));
    }

    private Object sortedJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new java.util.TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), sortedJson(item)));
            return sorted;
        }
        return value instanceof List<?> list ? list.stream().map(this::sortedJson).toList() : value;
    }

    private void gpu(long id, String mode, String unitPrice, String amountLimit, long quantityLimit) {
        jdbc.update("UPDATE compute_product SET type='GPU',purchase_mode=?,unit_price=?,max_purchase_amount=?,max_purchase_quantity=?,min_amount=1,max_amount=10000 WHERE id=?",
                mode, new BigDecimal(unitPrice), new BigDecimal(amountLimit), quantityLimit, id);
    }

    private Map<String, Object> gpuQuote(long product, String amount, String quantity) {
        return tx.execute(s -> quotes.quote(1, fields("productId", product, "amount", amount, "quantity", quantity)));
    }

    @Test
    void quantityPriceAndIncomeUseExactDecimalMathAndRequestReplayConsumesQuotaOnce() {
        gpu(1, "QUANTITY", "12.12345678", "100", 3);
        var quoted = gpuQuote(1, "0.01", "3");
        equalMoney("36.37037034", quoted.get("debitToken"));
        equalMoney("3.63703703", quoted.get("expectedIncomeMinToken"));
        var order = purchase(1, quoted, "quantity-exact-001");
        assertEquals("QUANTITY", order.get("purchaseMode"));
        assertEquals(3L, number(order.get("purchaseQuantity")));
        equalMoney("12.12345678", order.get("purchaseUnitPrice"));
        equalMoney("36.37037034", order.get("purchaseAmountToken"));
        equalMoney("963.62962966", balance("PROJECT"));
        assertEquals(number(order.get("id")), number(purchase(1, quoted, "quantity-exact-001").get("id")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_order", Integer.class));
        assertEquals("server.compute.v18.productQuantityLimit", assertThrows(BusinessException.class, () -> gpuQuote(1, "0", "1")).getMessage());
    }

    @Test
    void maturedPurchasesKeepTheirLifetimeAmountQuotaAndAnotherProjectHasItsOwnQuota() {
        gpu(1, "AMOUNT", "0", "30", 0);
        gpu(2, "AMOUNT", "0", "30", 0);
        var first = purchase(1, gpuQuote(1, "20", null), "lifetime-first-001");
        jdbc.update("UPDATE compute_order SET status='COMPLETED' WHERE id=?", first.get("id"));
        assertEquals("server.compute.v18.productAmountLimit", assertThrows(BusinessException.class, () -> gpuQuote(1, "10.00000001", null)).getMessage());
        purchase(1, gpuQuote(1, "10", null), "lifetime-final-001");
        purchase(2, gpuQuote(2, "30", null), "lifetime-other-001");
        equalMoney("940", balance("PROJECT"));
    }

    @Test
    void switchingToQuantityKeepsHistoricalAmountButNeverInventsHistoricalUnits() {
        gpu(1, "AMOUNT", "0", "0", 0);
        var legacy = purchase(1, gpuQuote(1, "80", null), "legacy-amount-001");
        jdbc.update("UPDATE compute_order SET purchase_quantity=NULL,purchase_amount_token=NULL,purchase_mode=NULL,product_quote_json=NULL,status='COMPLETED' WHERE id=?", legacy.get("id"));
        gpu(1, "QUANTITY", "10", "100", 3);
        var quoted = gpuQuote(1, "0", "2");
        var quota = quotes.map(quoted.get("quota"));
        equalMoney("80", quota.get("purchasedAmountToken"));
        equalMoney("20", quota.get("remainingAmountToken"));
        assertEquals(0L, number(quota.get("purchasedQuantity")));
        purchase(1, quoted, "legacy-quantity-001");
        assertEquals("server.compute.v18.productAmountLimit", assertThrows(BusinessException.class, () -> gpuQuote(1, "0", "1")).getMessage());
    }

    @Test
    void previouslyIssuedQuotesRecheckQuotaInsideTheMemberLock() throws Exception {
        gpu(1, "QUANTITY", "10", "0", 3);
        var first = gpuQuote(1, "0", "2");
        var second = gpuQuote(1, "0", "2");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); try { purchase(1, first, "quota-race-first-001"); return true; } catch (BusinessException error) { assertEquals("server.compute.v18.productQuantityLimit", error.getMessage()); return false; } });
            var b = executor.submit(() -> { start.await(); try { purchase(1, second, "quota-race-second-001"); return true; } catch (BusinessException error) { assertEquals("server.compute.v18.productQuantityLimit", error.getMessage()); return false; } });
            start.countDown();
            assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            equalMoney("980", balance("PROJECT"));
            assertEquals(2L, jdbc.queryForObject("SELECT SUM(purchase_quantity) FROM compute_order", Long.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_product_quote WHERE used_at IS NOT NULL", Integer.class));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void quantityValidationRejectsFractionalNegativeAndExcessiveUnitsWithoutConsumingFunds() {
        gpu(1, "QUANTITY", "1", "0", 0);
        for (String value : List.of("0", "-1", "1.5", "1000000001", "invalid"))
            assertThrows(BusinessException.class, () -> gpuQuote(1, "100", value));
        equalMoney("1000", balance("PROJECT"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_order", Integer.class));
    }

    @Test
    void everyPurchaseModeRequiresAConfirmedQuoteWithoutConsumingFunds() {
        for (String mode : List.of("AMOUNT", "QUANTITY")) {
            gpu(1, mode, "10", "30", 3);
            assertEquals("server.compute.v10.purchaseQuoteRequired", assertThrows(BusinessException.class,
                    () -> tx.execute(s -> business.buyOrder(1, fields("requestId", "no-quote-"+mode, "productId", 1, "amount", "20", "quantity", "1")))).getMessage());
        }
        equalMoney("1000", balance("PROJECT"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_order", Integer.class));
    }

    @Test
    void historicalFixedValuationIsUsedAndMissingValuationCannotBypassAnAmountLimit() {
        gpu(1, "AMOUNT", "0", "0", 0);
        var old = purchase(1, gpuQuote(1, "50", null), "historical-value-001");
        jdbc.update("UPDATE compute_order SET currency='BTC',amount=0.001,purchase_amount_token=NULL,product_quote_json=? WHERE id=?",
                data.json(fields("assetRate", "50000")), old.get("id"));
        gpu(1, "AMOUNT", "0", "60", 0);
        var quoted = gpuQuote(1, "10", null);
        equalMoney("50", quotes.map(quoted.get("quota")).get("purchasedAmountToken"));
        jdbc.update("UPDATE compute_order SET product_quote_json=NULL WHERE id=?", old.get("id"));
        assertEquals("server.compute.v18.productLimitUnvalued", assertThrows(BusinessException.class, () -> gpuQuote(1, "1", null)).getMessage());
        gpu(1, "AMOUNT", "0", "0", 0);
        assertEquals(false, quotes.map(gpuQuote(1, "1", null).get("quota")).get("amountComplete"));
    }

    @Test
    void backendPersistsQuantityConfigurationAndRejectsInvalidPriceOrLimit() {
        jdbc.execute("ALTER TABLE compute_product ADD COLUMN IF NOT EXISTS image_url VARCHAR(500) DEFAULT ''");
        var catalog = new ComputeCatalogService(data, new org.token.util.PasswordUtils());
        var input = fields("id", 1, "name", "份数项目", "type", "GPU", "purchaseMode", "QUANTITY", "unitPrice", "1.12345678",
                "maxPurchaseAmount", "123.12345678", "maxPurchaseQuantity", 20, "durationDays", 10, "dailyRateMin", "1", "dailyRateMax", "2", "settlementMode", "C", "description", "说明");
        var saved = tx.execute(s -> catalog.save("products", input));
        assertEquals("QUANTITY", saved.get("purchaseMode"));
        equalMoney("1.12345678", saved.get("unitPrice"));
        equalMoney("123.12345678", saved.get("maxPurchaseAmount"));
        assertEquals(20L, number(saved.get("maxPurchaseQuantity")));
        input.remove("maxPurchaseAmount"); input.remove("maxPurchaseQuantity");
        var preserved = tx.execute(s -> catalog.save("products", input));
        equalMoney("123.12345678", preserved.get("maxPurchaseAmount"));
        assertEquals(20L, number(preserved.get("maxPurchaseQuantity")));
        for (String price : List.of("0", "-1", "0.000000001", "1000000000000000000")) {
            input.put("unitPrice", price);
            assertThrows(BusinessException.class, () -> tx.execute(s -> catalog.save("products", input)));
        }
        input.put("unitPrice", "1");
        for (String maximum : List.of("-1", "1.1", "1000000001")) {
            input.put("maxPurchaseQuantity", maximum);
            assertThrows(BusinessException.class, () -> tx.execute(s -> catalog.save("products", input)));
        }
    }

    @ParameterizedTest(name = "结算模式{0}单独展示收益范围和返还本金{1}")
    @CsvSource({"A,100", "B,0", "C,100", "D,0"})
    void quoteUsesFullTermSimpleIncomeAndKeepsPrincipalSeparate(String mode, String returnedPrincipal) {
        gpu(1, "AMOUNT", "0", "0", 0);
        jdbc.update("UPDATE compute_product SET daily_rate_max=2.5,settlement_mode=? WHERE id=1", mode);
        var quoted = gpuQuote(1, "100", null);
        equalMoney("10", quoted.get("expectedIncomeMinToken"));
        equalMoney("25", quoted.get("expectedIncomeMaxToken"));
        equalMoney(returnedPrincipal, quoted.get("principalReturnToken"));
        equalMoney("1", quoted.get("dailyRateMin"));
        equalMoney("2.5", quoted.get("dailyRateMax"));
        assertEquals(10, quoted.get("durationDays"));
        assertEquals(mode, quoted.get("settlementMode"));
        equalMoney("1000", balance("PROJECT"));
    }

    @Test
    void expectedIncomeRoundsTheFullTermWithTheExistingSettlementPrecision() {
        gpu(1, "AMOUNT", "0", "0", 0);
        jdbc.update("UPDATE compute_product SET duration_days=3,daily_rate_min=0.00000050,daily_rate_max=0.00000099 WHERE id=1");
        var quoted = gpuQuote(1, "1", null);
        equalMoney("0.00000001", quoted.get("expectedIncomeMinToken"));
        equalMoney("0.00000002", quoted.get("expectedIncomeMaxToken"));
    }

    @Test
    void persistedQuotePreservesAllEightDecimalPlacesForLargeAmounts() {
        String price = "99999999999999999.12345678";
        gpu(1, "AMOUNT", "0", "0", 0);
        jdbc.update("UPDATE compute_product SET max_amount=?,daily_rate_min=0,daily_rate_max=0 WHERE id=1", new BigDecimal(price));
        jdbc.update("UPDATE compute_wallet SET available=? WHERE member_id=1 AND wallet_type='PROJECT'", new BigDecimal(price).add(BigDecimal.ONE));
        var quote = gpuQuote(1, price, null);
        var order = purchase(1, quote, "precision-order-001");
        equalMoney(price, order.get("amount"));
        equalMoney(price, order.get("purchaseAmountToken"));
        equalMoney("1", balance("PROJECT"));
    }

}
